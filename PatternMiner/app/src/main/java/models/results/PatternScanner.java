package models.results;

import com.google.common.collect.Table;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import models.data.*;
import one.util.streamex.StreamEx;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.Duration;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static java.util.Map.Entry.comparingByValue;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

public class PatternScanner {

    private final static Logger LOGGER = Logger.getLogger(PatternScanner.class.getName());
    private static final String COMMA = ",";

    private String seqKey;
    private Pattern pattern;


    public PatternScanner(String seqKey) {
        this.seqKey = seqKey;
        LOGGER.setLevel(Level.INFO);
    }

    private void createRegex() {
        String sortedSeqKey = sortedPattern();

        // build regex
        StringBuilder ex = new StringBuilder();
        ex.append("(^| )");

        for (String code : sortedSeqKey.split(" ")) {
            if (code.equals("-2")) {
                break;
            } else if (code.equals("-1")) {
                ex.append(code + "(.*)");
            } else {
                ex.append(code + " ([^-]*)");
            }
        }

        LOGGER.info("Builded regex pattern > " + ex.toString());
        this.pattern = Pattern.compile(ex.toString());
    }

    public String sortedPattern() {
        String expanded = seqKey + " -1 -2";
        String[] codes = expanded.split(" ");
        SortedSet<String> itemset = new TreeSet<>(Comparator.comparingInt(Integer::parseInt));
        StringBuilder sortedCodes = new StringBuilder();

        for (String code : codes) {
            if (code.equals("-1")) {
                for (String item : itemset) {
                    sortedCodes.append(item).append(" ");
                }
                itemset.clear();
            }
            if (code.equals("-2")) {
                sortedCodes.append("-1 -2");
            } else {
                itemset.add(code);
            }
        }
        String sorted = sortedCodes.toString();

        return sorted;
    }

    private JsonArray buildLinksJSON(Map<ICDLink, Long> linksMap) {
        Map<ICDLink, Long> filteredLinkMap = linksMap.entrySet().stream()
                .filter(icdLinkEntry -> icdLinkEntry.getValue() >= 10)
                .sorted(comparingByValue(Comparator.reverseOrder()))
                .limit(250)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        return createLinks(filteredLinkMap);
    }


    public JsonArray getCommonCodesJSON(ResultsEntry resultsEntry) {
        return (JsonArray) loadJson(getCommonCodesFilePath(resultsEntry));
    }

    private JsonArray createLinks(Map<ICDLink, Long> linksMap) {
        JsonArray links = new JsonArray();

        for (Map.Entry<ICDLink, Long> icdLink : linksMap.entrySet()) {
            JsonObject link = new JsonObject();

            link.addProperty("source", icdLink.getKey().getSource().getSmallCode());
            link.addProperty("target", icdLink.getKey().getTarget().getSmallCode());
            link.addProperty("value", icdLink.getValue());

            links.add(link);
        }

        return links;
    }

    public JsonObject getCompleteIcdLinksJSON(SortedMap<GenderAgeGroup, ResultsEntry> pattern) {
        JsonObject obj = new JsonObject();

        for (Map.Entry<GenderAgeGroup, ResultsEntry> entry : pattern.entrySet()) {
            JsonArray fullIcdLinksGroup = getIcdLinksJSON(entry.getValue());
            obj.add(entry.getKey().toString(), fullIcdLinksGroup);
        }

        return obj;
    }

    private JsonArray getIcdLinksJSON(ResultsEntry resultsEntry) {
        return (JsonArray) loadJson(getFullIcdLinksFilePath(resultsEntry));
    }

    public void createInverseSearchFiles(Table.Cell<String, GenderAgeGroup, ResultsEntry> cell) {
        createInverseSearchFiles(cell.getValue());
    }

    public static <T, AI, I, AO, R> Collector<T, ?, R> groupingAdjacent(
            BiPredicate<? super T, ? super T> keepTogether,
            Collector<? super T, AI, ? extends I> inner,
            Collector<I, AO, R> outer
    ) {
        AI EMPTY = (AI) new Object();

        // Container to accumulate adjacent possibly null elements.  Adj can be in one of 3 states:
        // - Before first element: curGrp == EMPTY
        // - After first element but before first group boundary: firstGrp == EMPTY, curGrp != EMPTY
        // - After at least one group boundary: firstGrp != EMPTY, curGrp != EMPTY
        class Adj {

            T first, last;     // first and last elements added to this container
            AI firstGrp = EMPTY, curGrp = EMPTY;
            AO acc = outer.supplier().get();  // accumlator for completed groups

            void add(T t) {
                if (curGrp == EMPTY) /* first element */ {
                    first = t;
                    curGrp = inner.supplier().get();
                } else if (!keepTogether.test(last, t)) /* group boundary */ {
                    addGroup(curGrp);
                    curGrp = inner.supplier().get();
                }
                inner.accumulator().accept(curGrp, last = t);
            }

            void addGroup(AI group) /* group can be EMPTY, in which case this should do nothing */ {
                if (firstGrp == EMPTY) {
                    firstGrp = group;
                } else if (group != EMPTY) {
                    outer.accumulator().accept(acc, inner.finisher().apply(group));
                }
            }

            Adj merge(Adj other) {
                if (other.curGrp == EMPTY) /* other is empty */ {
                    return this;
                } else if (this.curGrp == EMPTY) /* this is empty */ {
                    return other;
                } else if (!keepTogether.test(last, other.first)) /* boundary between this and other*/ {
                    addGroup(this.curGrp);
                    addGroup(other.firstGrp);
                } else if (other.firstGrp == EMPTY) /* other container is single-group. prepend this.curGrp to other.curGrp*/ {
                    other.curGrp = inner.combiner().apply(this.curGrp, other.curGrp);
                } else /* other Adj contains a boundary.  this.curGrp+other.firstGrp form a complete group. */ {
                    addGroup(inner.combiner().apply(this.curGrp, other.firstGrp));
                }
                this.acc = outer.combiner().apply(this.acc, other.acc);
                this.curGrp = other.curGrp;
                this.last = other.last;
                return this;
            }

            R finish() {
                AO combined = outer.supplier().get();
                if (curGrp != EMPTY) {
                    addGroup(curGrp);
                    assert firstGrp != EMPTY;
                    outer.accumulator().accept(combined, inner.finisher().apply(firstGrp));
                }
                return outer.finisher().apply(outer.combiner().apply(combined, acc));
            }
        }
        return Collector.of(Adj::new, Adj::add, Adj::merge, Adj::finish);
    }


    private JsonElement buildCommonCodesJSON(Map<ICDCode, Long> fullCommonCodes) {
        Map<Integer, Map<ICDCode, Double>> commonCodes = new HashMap<>();

        String[] keys = seqKey.split(" ");
        for (String key : keys) {
            int group = Integer.parseInt(key);

            if (group == -1) {
                continue;
            }

            if (!commonCodes.containsKey(Integer.parseInt(key))) {
                commonCodes.put(group, new HashMap<>());
            }

            Map<ICDCode, Long> commonCodesInGroup = fullCommonCodes.entrySet().stream()
                    .filter(entry -> entry.getKey().getGroup().ordinal() == group)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            int sum = commonCodesInGroup.values().stream().mapToInt(entry -> entry.intValue()).sum();

            Map<ICDCode, Double> topTen = commonCodesInGroup.entrySet().stream()
                    .filter(entry -> entry.getValue() >= 0.05 * sum)
                    .sorted(comparingByValue(Comparator.reverseOrder())).limit(10)
                    .collect(Collectors.toMap(entry -> entry.getKey(), entry -> round(entry.getValue() / (1.0 * sum))));

            commonCodes.put(group, topTen);
        }

        return createCommonCodes(commonCodes);
    }

    private double round(double val) {
        return Math.round(val * 10000) / 100.0;
    }

    private JsonElement createCommonCodes(Map<Integer, Map<ICDCode, Double>> commonCodes) {
        JsonArray fullCommonCodes = new JsonArray();

        for (Map.Entry<Integer, Map<ICDCode, Double>> topGroupsCodes : commonCodes.entrySet()) {
            JsonObject groupCommonCodes = new JsonObject();

            groupCommonCodes.addProperty("groupOrdinal", topGroupsCodes.getKey());
            groupCommonCodes.addProperty("groupName", DiagnosesGroup.values()[topGroupsCodes.getKey()].name());

            JsonArray topCodes = new JsonArray();

            for (Map.Entry<ICDCode, Double> topCode : topGroupsCodes.getValue().entrySet()) {
                JsonObject code = new JsonObject();
                code.addProperty("code", topCode.getKey().getSmallCode());
                code.addProperty("count", topCode.getValue());

                topCodes.add(code);
            }

            groupCommonCodes.add("topCodes", topCodes);

            fullCommonCodes.add(groupCommonCodes);
        }
        return fullCommonCodes;
    }

    private String getFullIcdLinksFilePath(ResultsEntry entry) {
        String scanName = entry.getGroupFileOfResult().getName().replace(".csv", "_fl.json").replace("_sorted", "");
        return getScanDir(entry.getFileOfResult()) + File.separator + scanName;
    }

    private String getCommonCodesFilePath(ResultsEntry entry) {
        String scanName = entry.getGroupFileOfResult().getName().replace(".csv", "_cc.json").replace("_sorted", "");
        return getScanDir(entry.getFileOfResult()) + File.separator + scanName;
    }


    private String getScanDir(ResultsDataFile file) {
        return file.getParent() + File.separator + "scans" + File.separator + "scan_ " + seqKey;
    }

    private boolean checkIfFileCreated(String path) {
        File file = new File(path);
        return file.exists();
    }


    private void saveJson(JsonElement object, String filePath) {
        File file = new File(filePath);
        OutputStream outputStream = null;
        Gson gson = new GsonBuilder().enableComplexMapKeySerialization().setPrettyPrinting().create();
        try {
            outputStream = new FileOutputStream(file);
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));

            gson.toJson(object, bufferedWriter);
            bufferedWriter.close();

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private JsonElement loadJson(String filePath) {
        JsonElement jsonElement = null;

        File file = new File(filePath);
        InputStream inputStream = null;


        try {
            inputStream = new FileInputStream(file);
            InputStreamReader streamReader = new InputStreamReader(inputStream, "UTF-8");
            JsonReader reader = new JsonReader(streamReader);
            JsonParser parser = new JsonParser();

            jsonElement = parser.parse(reader);

            streamReader.close();

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return jsonElement;
    }

    public boolean checkInverseSearchFiles(ResultsEntry entry) {
        if (!checkIfFileCreated(getScanDir(entry.getFileOfResult()))) {
            return false;
        }
        return (checkIfFileCreated(getCommonCodesFilePath(entry)) && checkIfFileCreated(getFullIcdLinksFilePath(entry)));
    }

    public void createInverseSearchFiles(ResultsEntry entry) {
        createRegex();

        File dir = new File(getScanDir(entry.getFileOfResult()));
        dir.mkdirs();

        String commonCodesFilePath = getCommonCodesFilePath(entry);
        String icdLinksFilePath = getFullIcdLinksFilePath(entry);

        if (!entry.isInverseSearch()) {
            boolean checkCommonCodes = checkIfFileCreated(commonCodesFilePath);
            boolean checkIcdLinks = checkIfFileCreated(icdLinksFilePath);

            if (!checkCommonCodes || !checkIcdLinks) {
                try {
                    DateTime timeStart = DateTime.now();

                    Set<ICDSequence> icdSequences = StreamEx.ofLines(Paths.get(entry.getGroupFileOfResult().getPath()))
                            .map(line -> line.split(COMMA))
                            .filter(transaction -> transaction.length > 2)
                            .filter(transaction -> transaction[1].length() == 8)
                            .filter(transaction -> transaction[1].startsWith("20"))
                            .groupRuns((transaction1, transaction2) -> transaction1[0].equals(transaction2[0]))
                            .map(ICDSequence::new)
                            .filter(sequence -> sequence.getFilteredDiagnosesCount() > 2)
                            .filter(sequence -> pattern.matcher(sequence.getFormatedSeqSPMF()).find())
                            .collect(Collectors.toSet());

                    DateTime timeCollected = DateTime.now();

                    if (!checkCommonCodes) {
                        Map<ICDCode, Long> commonCodes = icdSequences.stream()
                                .map(ICDSequence::getAllIcdCodes)
                                .flatMap(Collection::stream)
                                .collect(groupingBy(identity(), counting()));

                        saveJson(buildCommonCodesJSON(commonCodes), commonCodesFilePath);
                    }

                    DateTime timeCC = DateTime.now();

                    if (!checkIfFileCreated(icdLinksFilePath)) {
                        Map<ICDLink, Long> icdLinks = icdSequences.stream()
                                .map(ICDSequence::getIcdLinks)
                                .flatMap(Collection::stream)
                                .collect(groupingBy(identity(), counting()));
                        saveJson(buildLinksJSON(icdLinks), icdLinksFilePath);
                    }

                    DateTime timeLinks = DateTime.now();

                    LOGGER.info("Collected in: " + DateTimeUtils.getDurationMillis(new Duration(timeStart, timeCollected)) + " ms");
                    LOGGER.info("CC in: " + DateTimeUtils.getDurationMillis(new Duration(timeCollected, timeCC)) + " ms");
                    LOGGER.info("Links in: " + DateTimeUtils.getDurationMillis(new Duration(timeCC, timeLinks)) + " ms");
                } catch (IOException e) {
                    LOGGER.warning(e.getMessage());
                }
            }
        }

        entry.setInverseSearch(true);
    }
}
