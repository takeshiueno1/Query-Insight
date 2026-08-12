package com.query.insight.talent.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ClamAvFileScanClientTests {
    @Test
    void streamsContentAndMapsOkResponseToClean() throws Exception {
        byte[] expected = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newSingleThreadExecutor()) {
            var received = executor.submit(() -> serveOneScan(server, "stream: OK\0"));
            var client = new ClamAvFileScanClient("127.0.0.1", server.getLocalPort(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1));

            assertThat(client.scan(expected)).isEqualTo(FileScanClient.Result.CLEAN);
            assertThat(received.get()).isEqualTo(expected);
        }
    }

    @Test
    void mapsFoundAndConnectionFailureWithoutLeakingException() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newSingleThreadExecutor()) {
            var received = executor.submit(() -> serveOneScan(server, "stream: Eicar-Test-Signature FOUND\0"));
            var client = new ClamAvFileScanClient("127.0.0.1", server.getLocalPort(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1));
            assertThat(client.scan(new byte[] {1, 2, 3})).isEqualTo(FileScanClient.Result.INFECTED);
            assertThat(received.get()).containsExactly(1, 2, 3);
        }
        try (ServerSocket unavailable = new ServerSocket(0)) {
            int port = unavailable.getLocalPort();
            unavailable.close();
            var client = new ClamAvFileScanClient("127.0.0.1", port,
                    Duration.ofMillis(100), Duration.ofMillis(100));
            assertThat(client.scan(new byte[] {1})).isEqualTo(FileScanClient.Result.ERROR);
        }
    }

    private byte[] serveOneScan(ServerSocket server, String response) throws Exception {
        try (var socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            assertThat(input.readNBytes(10)).isEqualTo("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            int length = input.readInt();
            byte[] content = input.readNBytes(length);
            assertThat(input.readInt()).isZero();
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return content;
        }
    }
}
