package com.example.irudpchat.irudp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The classifier must give the probabilities of the Python reference model (gbdt-vectors.txt). */
public class LossClassifierTest {
    private static LossClassifier model() throws IOException {
        InputStream in = LossClassifierTest.class.getResourceAsStream("/gbdt.txt");   // src/main/assets is a test resource folder
        assertNotNull("gbdt.txt is missing", in);
        try {
            return LossClassifier.load(in);
        } finally {
            in.close();
        }
    }

    @Test
    public void reproducesThePythonModelOn40HeldOutVectors() throws IOException {
        LossClassifier clf = model();
        InputStream in = LossClassifierTest.class.getResourceAsStream("/gbdt-vectors.txt");
        assertNotNull("gbdt-vectors.txt is missing from src/test/resources", in);
        int rows = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                double[] x = new double[6];
                for (int i = 0; i < 6; i++) x[i] = Double.parseDouble(t[i]);
                assertEquals(line, Double.parseDouble(t[6]), clf.prob(x), 1e-12);
                rows++;
            }
        }
        assertEquals(40, rows);
    }

    @Test
    public void featuresFollowEquation9() {
        List<Double> rtts = new ArrayList<>(Arrays.asList(100.0, 100.0, 100.0, 100.0, 120.0, 120.0, 120.0, 120.0));
        double[] x = LossClassifier.features(150, 100, rtts, 3, 0.02, 50, 0.5, 4000, 200);
        assertEquals(0.5, x[0], 1e-12);            // queueing: (150 - 100) / 100
        assertEquals(0.2, x[1], 1e-12);            // trend: (120 - 100) / 100
        assertEquals(3, x[2], 0);                  // length of the loss run
        assertEquals(2, x[3], 1e-12);              // 100 x loss EWMA
        assertEquals(1, x[4], 1e-12);              // in flight / (rate x min RTT) = 50 / (0.5 x 100)
        assertEquals(20, x[5], 1e-12);             // time since the last event in SRTTs
        assertTrue(LossClassifier.features(100, 100, rtts, 1, 0, 10, 0, 1e9, 1)[5] <= 50);
    }
}
