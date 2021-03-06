package test;

import algorithms.sequenceprediction.ipredict.database.Item;
import algorithms.sequenceprediction.ipredict.database.Sequence;
import algorithms.sequenceprediction.ipredict.database.SequenceDatabase;
import algorithms.sequenceprediction.ipredict.database.SequenceStatsGenerator;
import algorithms.sequenceprediction.ipredict.predictor.LZ78.LZ78Predictor;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;

/**
 * Example of how to use the LZ78 sequence prediction model in the source code.
 * Copyright 2015.
 */
public class MainTestLz78 {

    public static void main(String[] arg) throws IOException {

        // Load the set of training sequences
        String inputPath = fileToPath("contextCPT.txt");
        SequenceDatabase trainingSet = new SequenceDatabase();
        trainingSet.loadFileSPMFFormat(inputPath, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);

        // Print the training sequences to the console
        System.out.println("--- Training sequences ---");
        for (Sequence sequence : trainingSet.getSequences()) {
            System.out.println(sequence.toString());
        }
        System.out.println();


        // Print statistics about the training sequences
        SequenceStatsGenerator.prinStats(trainingSet, " Statistics about training sequences ");

        // Train the prediction model
        LZ78Predictor predictionModel = new LZ78Predictor("LZ78");
        predictionModel.Train(trainingSet.getSequences());

        // Now we will make a prediction.
        // We want to predict what would occur after the sequence <1, 3>.
        // We first create the sequence
        Sequence sequence = new Sequence(0);
        sequence.addItem(new Item(1));
        sequence.addItem(new Item(4));

        // Then we perform the prediction
        System.out.println("--- Prediction ---");
        Sequence thePrediction = predictionModel.Predict(sequence);
        System.out.println("For the sequence <(1),(4)>, the prediction for the next symbol is: +" + thePrediction);

    }

    public static String fileToPath(String filename) throws UnsupportedEncodingException {
        URL url = MainTestLz78.class.getResource(filename);
        return java.net.URLDecoder.decode(url.getPath(), "UTF-8");
    }
}
