package com.query.insight.talent.attachment;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClamAvFileScanClient implements FileScanClient {
    private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 8192;

    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ClamAvFileScanClient(
            @Value("${app.files.clamav.host:localhost}") String host,
            @Value("${app.files.clamav.port:3310}") int port,
            @Value("${app.files.clamav.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${app.files.clamav.read-timeout:PT30S}") Duration readTimeout) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public Result scan(byte[] content) {
        if (content == null || content.length == 0) return Result.ERROR;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.toIntExact(connectTimeout.toMillis()));
            socket.setSoTimeout(Math.toIntExact(readTimeout.toMillis()));
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write(COMMAND);
            for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
                int length = Math.min(CHUNK_SIZE, content.length - offset);
                output.writeInt(length);
                output.write(content, offset, length);
            }
            output.writeInt(0);
            output.flush();
            String response = new String(socket.getInputStream().readNBytes(4096), StandardCharsets.US_ASCII);
            if (response.contains(" FOUND")) return Result.INFECTED;
            if (response.contains(" OK")) return Result.CLEAN;
            return Result.ERROR;
        } catch (IOException | ArithmeticException exception) {
            return Result.ERROR;
        }
    }
}
