package com.pis.idempotency;

import com.pis.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonicalizes a validated, typed command; never use a raw request string or omit semantic fields. */
@Component
public final class CanonicalRequestDigest {
    public static final int VERSION = 1;
    private static final int MAX_CANONICAL_BYTES = 65_536;
    private final JsonMapper json;

    public CanonicalRequestDigest(JsonMapper json) { this.json = json; }

    public byte[] request(Object validatedCommand) {
        if (validatedCommand == null) throw new IllegalArgumentException("A typed command is required");
        var canonical = new StringBuilder();
        canonicalize(json.valueToTree(validatedCommand), canonical, 0);
        var bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CANONICAL_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The command is too large");
        }
        return sha256(bytes);
    }

    public byte[] key(String key) {
        if (key == null) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key has an invalid format");
        }
        return sha256(key.getBytes(StandardCharsets.US_ASCII));
    }

    private void canonicalize(JsonNode node, StringBuilder target, int depth) {
        if (depth > 32 || target.length() > MAX_CANONICAL_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The command is too large or deeply nested");
        }
        if (node.isObject()) {
            var names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            target.append('{');
            boolean first = true;
            for (var name : names) {
                if (!first) target.append(',');
                first = false;
                target.append(json.writeValueAsString(name)).append(':');
                canonicalize(node.get(name), target, depth + 1);
            }
            target.append('}');
        } else if (node.isArray()) {
            target.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) target.append(',');
                canonicalize(node.get(index), target, depth + 1);
            }
            target.append(']');
        } else if (node.isString()) {
            target.append(json.writeValueAsString(node.stringValue()));
        } else if (node.isNumber()) {
            // One numeric value has one representation; no huge exponent expansion via toPlainString.
            target.append(node.decimalValue().stripTrailingZeros().toString());
        } else if (node.isBoolean()) {
            target.append(node.booleanValue());
        } else if (node.isNull()) {
            target.append("null");
        } else {
            throw new IllegalArgumentException("Unsupported canonical command value");
        }
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is required", error); }
    }
}
