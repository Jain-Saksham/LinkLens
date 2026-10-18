package com.linklens.service;

import com.linklens.config.AppProperties;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Random Base62 codes. SecureRandom makes codes unguessable, so links can't be enumerated
 * by walking a sequence (which a Base62-encoded database ID would allow).
 */
@Component
public class ShortCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public ShortCodeGenerator(AppProperties properties) {
        this.length = properties.shortCodeLength();
    }

    public String generate() {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
