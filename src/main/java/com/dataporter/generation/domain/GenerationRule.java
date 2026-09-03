package com.dataporter.generation.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface GenerationRule permits GenerationRule.Literal, GenerationRule.RandomString,
        GenerationRule.RandomAlphaNumStringBetween, GenerationRule.RandomNumber,
        GenerationRule.WeightedChoice, GenerationRule.RandomBoolean,
        GenerationRule.Sequence, GenerationRule.ObjectId, GenerationRule.Uuid, GenerationRule.DateTime,
        GenerationRule.Ref, GenerationRule.Concat, GenerationRule.Array, GenerationRule.ObjectValue {

    RuleOptions options();

    record RuleOptions(double nullProbability, double omitProbability) {
        public static final RuleOptions REQUIRED = new RuleOptions(0, 0);
        public RuleOptions {
            if (!Double.isFinite(nullProbability) || !Double.isFinite(omitProbability)
                    || nullProbability < 0 || omitProbability < 0 || nullProbability > 1
                    || omitProbability > 1 || nullProbability + omitProbability > 1)
                throw new IllegalArgumentException("nullProbability and omitProbability must be in [0,1] and sum to at most 1");
        }
    }

    record Literal(Object value, RuleOptions options) implements GenerationRule { }

    enum Alphabet { UPPER_LATIN, LOWER_LATIN, ALPHANUMERIC, HEX, CUSTOM }
    record RandomString(Alphabet alphabet, String characters, int minLength, int maxLength,
                        RuleOptions options) implements GenerationRule {
        public RandomString {
            if (alphabet == null) throw new IllegalArgumentException("randomString alphabet is required");
            if (minLength < 0 || maxLength < minLength) throw new IllegalArgumentException("invalid randomString length range");
            if (maxLength > 16 * 1024 * 1024) throw new IllegalArgumentException("randomString length exceeds BSON document limit");
            if (alphabet == Alphabet.CUSTOM && (characters == null || characters.isEmpty()))
                throw new IllegalArgumentException("CUSTOM randomString characters are required");
        }
    }

    record RandomAlphaNumStringBetween(BigInteger min, BigInteger max, int length,
                                       RuleOptions options) implements GenerationRule {
        public RandomAlphaNumStringBetween {
            if (min == null || max == null || min.signum() < 0 || min.compareTo(max) >= 0)
                throw new IllegalArgumentException("randomAlphaNumStringBetween requires 0 <= min < max");
            if (max.subtract(min).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
                throw new IllegalArgumentException("randomAlphaNumStringBetween range exceeds BSON int64-sized offset");
            if (length <= 0)
                throw new IllegalArgumentException("randomAlphaNumStringBetween length must be positive");
            if (length > 16 * 1024 * 1024)
                throw new IllegalArgumentException("randomAlphaNumStringBetween length exceeds BSON document limit");
            if (max.subtract(BigInteger.ONE).toString(36).length() > length)
                throw new IllegalArgumentException("randomAlphaNumStringBetween max does not fit configured length");
        }
    }

    enum NumberType { INT32, INT64, DOUBLE, DECIMAL128 }
    record RandomNumber(NumberType bsonType, BigDecimal min, BigDecimal max,
                        RuleOptions options) implements GenerationRule {
        public RandomNumber {
            if (bsonType == null || min == null || max == null || min.compareTo(max) > 0)
                throw new IllegalArgumentException("invalid randomNumber type or range");
            if ((bsonType == NumberType.INT32 || bsonType == NumberType.INT64)
                    && (min.stripTrailingZeros().scale() > 0 || max.stripTrailingZeros().scale() > 0))
                throw new IllegalArgumentException("integer randomNumber bounds must be integral");
            if (bsonType == NumberType.INT32 && (min.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                    || max.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0))
                throw new IllegalArgumentException("INT32 randomNumber bounds exceed BSON int32");
            if (bsonType == NumberType.INT64) {
                try { min.longValueExact(); max.longValueExact(); }
                catch (ArithmeticException e) { throw new IllegalArgumentException("INT64 randomNumber bounds exceed BSON int64"); }
            }
            if (bsonType == NumberType.DOUBLE && (!Double.isFinite(min.doubleValue()) || !Double.isFinite(max.doubleValue())))
                throw new IllegalArgumentException("DOUBLE randomNumber bounds must be finite");
        }
    }

    record Choice(GenerationRule value, double weight) {
        public Choice {
            if (value == null) throw new IllegalArgumentException("choice value is required");
            if (!Double.isFinite(weight) || weight <= 0)
                throw new IllegalArgumentException("choice weight must be positive");
        }
        public Choice(Object literal, double weight) {
            this(new Literal(literal, RuleOptions.REQUIRED), weight);
        }
    }
    final class WeightedChoice implements GenerationRule {
        private final List<Choice> choices;
        private final RuleOptions options;
        private final double[] cumulativeWeights;

        public WeightedChoice(List<Choice> choices, RuleOptions options) {
            this.choices = List.copyOf(choices);
            this.options = Objects.requireNonNull(options, "weightedChoice options are required");
            if (this.choices.isEmpty())
                throw new IllegalArgumentException("weightedChoice choices must not be empty");
            this.cumulativeWeights = new double[this.choices.size()];
            double total = 0;
            for (int i = 0; i < this.choices.size(); i++) {
                total += this.choices.get(i).weight();
                if (!Double.isFinite(total))
                    throw new IllegalArgumentException("weightedChoice total weight must be finite");
                cumulativeWeights[i] = total;
            }
        }

        public List<Choice> choices() { return choices; }
        @Override public RuleOptions options() { return options; }

        public GenerationRule select(double coordinate) {
            if (!Double.isFinite(coordinate) || coordinate < 0 || coordinate >= 1)
                throw new IllegalArgumentException("weightedChoice coordinate must be in [0,1)");
            double selected = coordinate * cumulativeWeights[cumulativeWeights.length - 1];
            for (int i = 0; i < cumulativeWeights.length; i++)
                if (selected < cumulativeWeights[i]) return choices.get(i).value();
            return choices.getLast().value();
        }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof WeightedChoice that
                    && choices.equals(that.choices) && options.equals(that.options);
        }
        @Override public int hashCode() { return Objects.hash(choices, options); }
        @Override public String toString() {
            return "WeightedChoice[choices=" + choices + ", options=" + options + "]";
        }
    }

    record RandomBoolean(double trueProbability, RuleOptions options) implements GenerationRule {
        public RandomBoolean {
            if (!Double.isFinite(trueProbability) || trueProbability < 0 || trueProbability > 1)
                throw new IllegalArgumentException("trueProbability must be in [0,1]");
        }
    }

    enum SequenceStart { EXPLICIT, AUTO_AFTER_TARGET_MAX }
    record Sequence(SequenceStart start, long explicitStart, long step, RuleOptions options) implements GenerationRule {
        public Sequence { if (start == null || step == 0) throw new IllegalArgumentException("sequence start and non-zero step are required"); }
    }

    record ObjectId(RuleOptions options) implements GenerationRule { }
    enum UuidOutput { BSON_BINARY, STRING }
    record Uuid(UuidOutput output, RuleOptions options) implements GenerationRule {
        public Uuid { if (output == null) throw new IllegalArgumentException("uuid output is required"); }
    }

    sealed interface DateSource permits RandomDateRange, FixedDate, DateRef, SharedDateRef { }
    record RandomDateRange(Instant from, Instant to) implements DateSource {
        public RandomDateRange { if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("invalid dateTime range"); }
    }
    record FixedDate(Instant value) implements DateSource {
        public FixedDate { if (value == null) throw new IllegalArgumentException("fixed date value is required"); }
    }
    record DateRef(String collection, String path) implements DateSource {
        public DateRef { if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("date ref path must be JSON Pointer"); }
    }
    record SharedDateRef(String name) implements DateSource {
        public SharedDateRef {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("shared date name is required");
        }
    }
    enum DateOutput { BSON_DATE, STRING }
    record DateTime(DateSource source, DateOutput output, String pattern, String zone, String locale,
                    RuleOptions options) implements GenerationRule {
        public DateTime {
            if (source == null || output == null) throw new IllegalArgumentException("dateTime source and output are required");
            if (output == DateOutput.STRING && (pattern == null || pattern.isBlank()))
                throw new IllegalArgumentException("dateTime STRING pattern is required");
        }
    }

    enum MissingPolicy { ERROR, NULL, OMIT }
    record Ref(String collection, String path, MissingPolicy onMissing,
               RuleOptions options) implements GenerationRule {
        public Ref {
            if (path == null || !path.startsWith("/") || onMissing == null)
                throw new IllegalArgumentException("ref path must be JSON Pointer and onMissing is required");
        }
    }
    record Concat(List<GenerationRule> parts, RuleOptions options) implements GenerationRule {
        public Concat { parts = List.copyOf(parts); if (parts.isEmpty()) throw new IllegalArgumentException("concat parts must not be empty"); }
    }
    record LengthRange(int min, int max) {
        public LengthRange {
            if (min < 0 || max < min) throw new IllegalArgumentException("invalid array length range");
            if (max > 1_000_000) throw new IllegalArgumentException("array length is too large for bounded generation");
        }
    }
    record Array(LengthRange length, GenerationRule items, RuleOptions options) implements GenerationRule {
        public Array { if (length == null || items == null) throw new IllegalArgumentException("array length and items are required"); }
    }
    record ObjectValue(Map<String, GenerationRule> fields, RuleOptions options) implements GenerationRule {
        public ObjectValue { fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields)); }
    }
}
