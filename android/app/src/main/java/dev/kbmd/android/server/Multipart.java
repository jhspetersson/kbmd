package dev.kbmd.android.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for {@code multipart/form-data} bodies, reading part headers as UTF-8 so file names survive. */
final class Multipart {

    static final class Part {
        final String name;
        /** Null for plain form fields. */
        final String fileName;
        final byte[] body;
        final int start;
        final int length;

        Part(String name, String fileName, byte[] body, int start, int length) {
            this.name = name;
            this.fileName = fileName;
            this.body = body;
            this.start = start;
            this.length = length;
        }

        String text() {
            return new String(body, start, length, StandardCharsets.UTF_8);
        }
    }

    private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("[;\\s]name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_NAME = Pattern.compile("[;\\s]filename=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private Multipart() {
    }

    static List<Part> parse(String contentType, byte[] body) {
        Matcher boundary = BOUNDARY.matcher(contentType == null ? "" : contentType);
        if (!boundary.find()) {
            throw HttpError.badRequest("Not a multipart request");
        }
        byte[] delimiter = ("--" + (boundary.group(1) != null ? boundary.group(1) : boundary.group(2))).getBytes(StandardCharsets.US_ASCII);
        byte[] headerEnd = {'\r', '\n', '\r', '\n'};

        List<Part> parts = new ArrayList<>();
        int at = indexOf(body, delimiter, 0);
        while (at >= 0) {
            int cursor = at + delimiter.length;
            if (cursor + 1 < body.length && body[cursor] == '-' && body[cursor + 1] == '-') {
                break; // closing delimiter
            }
            cursor += 2; // CRLF after the delimiter
            int headersEnd = indexOf(body, headerEnd, cursor);
            if (headersEnd < 0) {
                break;
            }
            String headers = " " + new String(body, cursor, headersEnd - cursor, StandardCharsets.UTF_8);
            int dataStart = headersEnd + headerEnd.length;
            int next = indexOf(body, delimiter, dataStart);
            if (next < 0) {
                break;
            }
            int dataEnd = Math.max(dataStart, next - 2); // CRLF before the delimiter

            Matcher name = NAME.matcher(headers);
            Matcher fileName = FILE_NAME.matcher(headers);
            if (name.find()) {
                parts.add(new Part(name.group(1), fileName.find() ? fileName.group(1).replace("%22", "\"") : null,
                        body, dataStart, dataEnd - dataStart));
            }
            at = next;
        }
        return parts;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
