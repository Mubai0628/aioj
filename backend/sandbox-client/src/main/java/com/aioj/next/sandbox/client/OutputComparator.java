package com.aioj.next.sandbox.client;

import java.io.IOException;
import java.io.InputStream;

public interface OutputComparator {
    ComparisonResult compare(InputStream expectedOutput, InputStream actualOutput) throws IOException;

    record ComparisonResult(boolean accepted, String message) {
    }
}
