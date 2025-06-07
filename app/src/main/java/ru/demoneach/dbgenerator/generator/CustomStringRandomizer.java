package ru.demoneach.dbgenerator.generator;

import lombok.AllArgsConstructor;
import org.jeasy.random.api.Randomizer;

import java.util.Random;

@AllArgsConstructor
public class CustomStringRandomizer implements Randomizer<String> {

    private static final String ALLOWED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MIN_LENGTH = 5;
    private static final int MAX_LENGTH = 10;
    private final Random rand = new Random();

    @Override
    public String getRandomValue() {
        int length = MIN_LENGTH + (rand.nextInt(MIN_LENGTH, MAX_LENGTH));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int index = (rand.nextInt(0, ALLOWED_CHARS.length()));
            sb.append(ALLOWED_CHARS.charAt(index));
        }
        return sb.toString();
    }
}

