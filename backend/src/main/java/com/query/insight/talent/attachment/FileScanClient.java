package com.query.insight.talent.attachment;

public interface FileScanClient {
    Result scan(byte[] content);

    enum Result {
        CLEAN,
        INFECTED,
        ERROR
    }
}
