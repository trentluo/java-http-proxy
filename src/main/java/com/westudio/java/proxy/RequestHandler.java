package com.westudio.java.proxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.westudio.java.socket.pool.HostInfo;
import com.westudio.java.socket.pool.SocketConnection;
import com.westudio.java.socket.pool.SocketPool;
import com.westudio.java.util.Numbers;
import com.westudio.java.util.Streams;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: tonyhe
 * Date: 14-5-26
 * Time: 下午6:41
 * To change this template use File | Settings | File Templates.
 */
public class RequestHandler implements HttpHandler {

    private SocketPool socketPool = new SocketPool(new GenericKeyedObjectPoolConfig());

    private static HashSet<String> SKIP_HEADERS = new HashSet<>(Arrays.asList(
            "ACCESS-CONTROL-REQUEST-METHODS",
            "ACCESS-CONTROL-REQUEST-HEADERS",
            "CONNECTION",
            "CONTENT-LENGTH",
            "CONTENT-TRANSFER-ENCODING",
            "KEEP-ALIVE",
            "TRAILER",
            "TRANSFER-ENCODING",
            "UPGRADE",
            "VIA",
            "X-FORWARDED-FOR",
            "X-FORWARDED-PROTO",
            "X-PKCS7-CERTIFICATES-BASE64",
            "DNT"
    ));
    private static final int RECV_MAX_CHUNK_SIZE = 4095;
    private static final int RESP_MAX_SIZE = 65536;
    private static final String HEX_DIGITS = "0123456789ABCDEF";

    public RequestHandler() {
    }

    private static void write(OutputStream ops, String str) throws IOException {
        ops.write(str.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void writeln(OutputStream ops) throws IOException {
        ops.write('\r');
        ops.write('\n');
    }

    private static void writeHeader(OutputStream ops, String key, String value) throws IOException {
        write(ops, key);
        ops.write(':');
        ops.write(' ');
        write(ops, value);
        writeln(ops);
    }

    private static void copyResponse(InputStream is, OutputStream os, byte[] buffer, int length) throws IOException {
        int bytesToRead = length;
        while (bytesToRead > 0) {
            int bytesRead = is.read(buffer, 0, Math.min(RESP_MAX_SIZE, bytesToRead));
            if (bytesRead < 0) {
                throw new IOException("Connection lost");
            }
            if (bytesRead == 0) {
                throw new IOException("Zero bytes read");
            }

            bytesToRead -= bytesRead;
            try {
                os.write(buffer, 0, bytesRead);
            } catch (IOException e) {
                if (bytesToRead > RESP_MAX_SIZE) {
                    throw new IOException("To many bytes");
                }
            }
        }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {

        URI uri = httpExchange.getRequestURI();
        String schema = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        port = port < 0 ? ("https".equals(schema) ? 443 : 80) : port;

        String query = uri.getQuery();
        String path = uri.getPath();
        String method = httpExchange.getRequestMethod();

        SocketConnection socketConnection = null;
        try {
            socketConnection = socketPool.getResource(new HostInfo(host, port));
            // Add request header
            BufferedOutputStream outputStream = new BufferedOutputStream(socketConnection.getSocket().getOutputStream());
            write(outputStream, method);
            outputStream.write(' ');
            write(outputStream, path);
            write(outputStream, " HTTP/1.1");
            writeln(outputStream);

            boolean withHost = false;
            int contentLength = -1;
            for (Map.Entry<String, List<String>> entry : httpExchange.getRequestHeaders().entrySet()) {
                String key = entry.getKey();

                if (key.equalsIgnoreCase("Host")) {
                    withHost = true;
                }

                if (SKIP_HEADERS.contains(key.toUpperCase())) {
                    if (key.equalsIgnoreCase("Content-Length")) {
                        List<String> value = entry.getValue();
                        if (!value.isEmpty()) {
                            contentLength = Numbers.parseInt(value.get(0));
                        }
                    }
                    continue;
                }

                for (String value : entry.getValue()) {
                    if (key.equalsIgnoreCase("Destination")) {
                        if ("https".equals(schema) && value.startsWith("https")) {
                            value = "http:" + value.substring(6);
                        }
                    }

                    writeHeader(outputStream, key, value);
                }
            }

            if (!withHost) {
                writeHeader(outputStream, "Host", host + ":" + port);
            }
            writeHeader(outputStream, "Connection", "Keep-Alive");

            if (contentLength > 0) {
                writeHeader(outputStream, "Content-Length", "" + contentLength);
                writeln(outputStream);
                // Simply pipe the input stream to the output stream
                Streams.pipe(httpExchange.getRequestBody(), outputStream);
            } else if (contentLength == 0) {
                writeHeader(outputStream, "Content-Length", "0");
                writeln(outputStream);
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                writeln(outputStream);
            } else {
                // Handle chunked transfer encoding
                writeHeader(outputStream, "Transfer-Encoding", "chunked");
                InputStream is = httpExchange.getRequestBody();
                byte[] buffer = new byte[RECV_MAX_CHUNK_SIZE];
                buffer[3] = '\r';
                buffer[4] = '\n';
                int bytesRead;
                while ((bytesRead = is.read(buffer, 5, RECV_MAX_CHUNK_SIZE)) > 0) {
                    buffer[0] = (byte)HEX_DIGITS.charAt(bytesRead / 256);
                    buffer[1] = (byte)HEX_DIGITS.charAt(bytesRead / 16 % 16);
                    buffer[2] = (byte)HEX_DIGITS.charAt(bytesRead % 16);
                    buffer[bytesRead + 5] = '\r';
                    buffer[bytesRead + 6] = '\n';
                    outputStream.write(buffer, 0, bytesRead + 7);
                }
                // Add the last block chunk of size 0
                outputStream.write('0');
                writeln(outputStream);
                writeln(outputStream);
            }
            outputStream.flush();

            // Generate response headers
            Headers headers = httpExchange.getResponseHeaders();
            InputStream is = socketConnection.getSocket().getInputStream();
            StringBuilder sb = new StringBuilder();
            int status = 0;
            boolean connectionClose = false;
            contentLength = 0;
            while (true) {
                int b = is.read();
                if (b < 0) {
                    throw new IOException("Connection Lost");
                }

                if (b == '\r') {
                    continue;
                }
                if (b != '\n') {
                    sb.append((char)b);
                    continue;
                }

                if (sb.length() == 0) {
                    if (status == 100) {
                        status = 0;
                        continue;
                    }
                    break;
                }

                if (status == 0) {
                    String[] ss = sb.toString().split(" ");
                    if (ss.length < 2) {
                        throw new IOException("Response error [" + sb + "]");
                    }
                    status = Numbers.parseInt(ss[1]);
                } else if (status != 100) {
                    int index = sb.indexOf(": ");
                    if (index >= 0) {
                        String key = sb.substring(0, index);
                        String value = sb.substring(index + 2);

                        if (SKIP_HEADERS.contains(key.toUpperCase())) {
                            if (key.equalsIgnoreCase("Content-Length")) {
                                contentLength = Numbers.parseInt(value);
                            } else if (key.equalsIgnoreCase("Transfer-Encoding")) {
                                if (value.equalsIgnoreCase("chunked")) {
                                    contentLength = -1;
                                }
                            } else if (!connectionClose) {
                                if (key.equalsIgnoreCase("Connection")) {
                                    connectionClose = value.equalsIgnoreCase("close");
                                }
                            }
                        } else {
                            headers.add(key, value);
                        }
                    }
                }

                sb.setLength(0);
            }

            // Handle response body
            if (contentLength == 0 && method == "HEAD") {
                httpExchange.sendResponseHeaders(status, -1);
                //TODO:RETURN THE SOCKET RESOURCE TO THE POOL
                return;
            }

            OutputStream os = httpExchange.getResponseBody();
            byte[] buffer = new byte[RESP_MAX_SIZE];
            if (contentLength > 0) {
                httpExchange.sendResponseHeaders(status, contentLength);
                copyResponse(is, os, buffer, contentLength);
                return;
            }

            // HANDLE THE CHUNKED RESPONSE BODY
            httpExchange.sendResponseHeaders(status, 0);
            int chunkSize = 0;
            boolean crlf = false;
            while (true) {
                int b = is.read();
                if (b < 0) {
                    throw new IOException("Connection lost");
                }
                if (crlf) {
                    if (b == '\n') {
                        chunkSize = 0;
                        crlf = false;
                    }
                } else if (b == '\n') {
                    if (chunkSize <= 0) {
                        break;
                    }
                    copyResponse(is, os, buffer, chunkSize);
                    crlf = true;
                } else {
                    b = HEX_DIGITS.indexOf(Character.toUpperCase(b));
                    if (b >= 0) {
                        chunkSize = chunkSize * 16 + b; //FIXME:CORRECT?
                    }
                }
            }
        } catch (IOException e) {
            //TODO:HANDLE EXCEPTION
        } finally {
            //FIXME
            if (socketPool != null) {
                socketPool.returnResource(socketConnection);
            }
            httpExchange.sendResponseHeaders(502, -1);
            httpExchange.close();
        }
    }
}
