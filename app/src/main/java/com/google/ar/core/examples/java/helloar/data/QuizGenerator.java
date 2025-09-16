package com.google.ar.core.examples.java.helloar.data;

import androidx.annotation.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Heuristic (local) quiz builder used as a fallback when Vertex AI isn't used. */
public final class QuizGenerator {

    // Fallback pools when we don't have enough distractors in the text
    private static final String[] FALLBACK_PLACES = {
            "City Library","Central Park","Main Auditorium","Science Museum",
            "Tech Hub","Town Hall","Art Gallery","Riverfront"
    };
    private static final String[] FALLBACK_NAMES = {
            "Heritage Block","Innovation Center","Discovery Hall","Unity Plaza",
            "Nexus Building","Skylark Tower","Harmony Wing","Pioneer House"
    };

    // ---- Generic trivia fallback (used when text yields nothing) ----
    private static final class Trivia {
        final String q; final List<String> opts; final int correct;
        Trivia(String q, List<String> opts, int correct) { this.q = q; this.opts = opts; this.correct = correct; }
    }
    private static Trivia T(String q, String a, String b, String c, String d, int correctIdx) {
        return new Trivia(q, Arrays.asList(a,b,c,d), correctIdx);
    }
    private static final List<Trivia> GENERIC_TRIVIA = Arrays.asList(
            T("What is the capital of India?", "New Delhi", "Mumbai", "Kolkata", "Chennai", 0),
            T("How many continents are there on Earth?", "7", "5", "6", "8", 0),
            T("Which is the largest planet in our solar system?", "Jupiter", "Earth", "Mars", "Venus", 0),
            T("What gas do plants mostly take in?", "Carbon dioxide", "Oxygen", "Nitrogen", "Helium", 0),
            T("Which ocean is the largest?", "Pacific Ocean", "Atlantic Ocean", "Indian Ocean", "Arctic Ocean", 0)
    );

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "the","a","an","and","or","but","if","then","else","when","while","for","to","of",
            "in","on","at","by","with","as","is","am","are","was","were","be","been","being",
            "this","that","these","those","it","its","from","into","out","over","under","about",
            "we","you","they","he","she","i","me","my","your","our","their","them","us"
    ));

    /** Build up to {@code maxQ} multiple-choice questions from free text. Always returns ≥ 1. */
    public static List<EggEntry.QuizQuestion> generate(@Nullable String rawText, int maxQ) {
        final int target = Math.max(1, maxQ);
        final List<EggEntry.QuizQuestion> out = new ArrayList<>();

        // Normalize (do NOT early-return; we want fallback even if empty)
        String text = (rawText == null) ? "" : rawText.replace('\n', ' ').replaceAll("\\s+", " ").trim();

        // Collect proper-noun phrases (for distractors)
        List<String> properPhrases = extractProperNounPhrases(text);

        if (!text.isEmpty()) {
            // Split into sentences and shuffle for variety
            String[] sentences = text.split("(?<=[.!?])\\s+");
            List<String> shuffled = new ArrayList<>(Arrays.asList(sentences));
            Collections.shuffle(shuffled, new Random());

            for (String s : shuffled) {
                EggEntry.QuizQuestion q;

                q = tryWhereQuestion(s, properPhrases);
                if (addIfValid(out, q, target)) break;

                q = tryWhenQuestion(s);
                if (addIfValid(out, q, target)) break;

                q = tryHowManyQuestion(s);
                if (addIfValid(out, q, target)) break;

                q = tryWhatNameQuestion(s, properPhrases);
                if (addIfValid(out, q, target)) break;
            }
        }

        // If still nothing, pick a simple, friendly fallback.
        if (out.isEmpty()) {
            if (!properPhrases.isEmpty()) {
                String qText = "Which place is mentioned here?";
                List<String> opts = new ArrayList<>();
                String correct = properPhrases.get(0);
                opts.add(correct);
                fillWithFallbacks(opts, Arrays.asList(FALLBACK_PLACES), 4);
                int idx = shuffleAndFindIndex(opts, correct);

                EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
                q.q = ensureQuestionMark(qText);
                q.question = q.q;              // keep both fields filled
                q.options = opts;
                q.answer = idx;
                q.correctIndex = idx;
                out.add(q);
            } else {
                Trivia t = GENERIC_TRIVIA.get(new Random().nextInt(GENERIC_TRIVIA.size()));
                EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
                q.q = ensureQuestionMark(t.q);
                q.question = q.q;              // keep both fields filled
                q.options = new ArrayList<>(t.opts);
                q.answer = t.correct;
                q.correctIndex = t.correct;
                out.add(q);
            }
        }

        // Cap to target
        if (out.size() > target) return new ArrayList<>(out.subList(0, target));
        return out;
    }

    // ---------- Question builders ----------

    // WHERE: “… in/at/inside/near/opposite/beside <ProperPhrase> …”
    private static EggEntry.QuizQuestion tryWhereQuestion(String s, List<String> properPool) {
        Pattern p = Pattern.compile("\\b(in|at|inside|within|near|around|opposite|beside)\\s+([A-Z][\\w&.-]*(?:\\s+[A-Z][\\w&.-]*)*)");
        Matcher m = p.matcher(s);
        if (!m.find()) return null;

        String place = m.group(2).trim();
        if (place.isEmpty()) return null;

        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.q = "Where is this located?";
        q.question = q.q;
        q.options = new ArrayList<>();
        q.options.add(place);

        // Use other proper nouns as distractors; fallback if needed
        List<String> distractors = new ArrayList<>();
        for (String pp : properPool) {
            if (!pp.equalsIgnoreCase(place)) distractors.add(pp);
        }
        if (distractors.isEmpty()) distractors = Arrays.asList(FALLBACK_PLACES);
        fillWithFallbacks(q.options, distractors, 4);

        q.answer = shuffleAndFindIndex(q.options, place);
        q.correctIndex = q.answer;
        return q;
    }

    // WHEN: “… built/constructed/opened/founded/renovated … <YEAR>”
    private static EggEntry.QuizQuestion tryWhenQuestion(String s) {
        Matcher ym = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(s);

        boolean hasVerb = Pattern.compile("\\b(built|constructed|opened|founded|renovated|established|inaugurated)\\b",
                Pattern.CASE_INSENSITIVE).matcher(s).find();

        if (!hasVerb || !ym.find()) return null;

        int year = Integer.parseInt(ym.group());
        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.q = "In which year was it " + pickVerb(s) + "?";
        q.question = q.q;

        // Options around the true year
        Set<String> opts = new LinkedHashSet<>();
        opts.add(String.valueOf(year));
        Random r = new Random();
        while (opts.size() < 4) {
            int delta = (r.nextInt(5) + 1); // 1..5
            int sign = (r.nextBoolean() ? 1 : -1);
            int candidate = year + sign * delta;
            if (candidate >= 1900 && candidate <= 2100) opts.add(String.valueOf(candidate));
        }
        q.options = new ArrayList<>(opts);
        q.answer = shuffleAndFindIndex(q.options, String.valueOf(year));
        q.correctIndex = q.answer;
        return q;
    }

    // HOW MANY: “… <number> floors/rooms/galleries/levels …”
    private static EggEntry.QuizQuestion tryHowManyQuestion(String s) {
        Matcher m = Pattern.compile("\\b(\\d{1,3})\\s+(floors?|rooms?|galleries?|levels?|buildings?)\\b",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (!m.find()) return null;

        int n = Integer.parseInt(m.group(1));
        String noun = m.group(2).toLowerCase(Locale.US);

        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.q = "How many " + noun + " are there?";
        q.question = q.q;

        Set<String> opts = new LinkedHashSet<>();
        opts.add(String.valueOf(n));
        // Nearby numbers as distractors
        for (int d : new int[]{n - 1, n + 1, n + 2, n - 2, n + 3}) {
            if (d > 0) opts.add(String.valueOf(d));
            if (opts.size() >= 4) break;
        }
        while (opts.size() < 4) opts.add(String.valueOf(n + opts.size())); // fallback

        q.options = new ArrayList<>(opts);
        q.answer = shuffleAndFindIndex(q.options, String.valueOf(n));
        q.correctIndex = q.answer;
        return q;
    }

    // WHAT NAME: “… called/named <ProperPhrase> …”
    private static EggEntry.QuizQuestion tryWhatNameQuestion(String s, List<String> properPool) {
        Matcher m = Pattern.compile("\\b(called|named)\\s+([A-Z][\\w.-]*(?:\\s+[A-Z][\\w.-]*)*)").matcher(s);
        if (!m.find()) return null;

        String name = m.group(2).trim();
        if (name.isEmpty()) return null;

        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.q = "What is it called?";
        q.question = q.q;
        q.options = new ArrayList<>();
        q.options.add(name);

        List<String> distractors = new ArrayList<>();
        for (String pp : properPool) {
            if (!pp.equalsIgnoreCase(name)) distractors.add(pp);
        }
        if (distractors.isEmpty()) distractors = Arrays.asList(FALLBACK_NAMES);
        fillWithFallbacks(q.options, distractors, 4);

        q.answer = shuffleAndFindIndex(q.options, name);
        q.correctIndex = q.answer;
        return q;
    }

    // ---------- Helpers ----------

    private static boolean addIfValid(List<EggEntry.QuizQuestion> out, @Nullable EggEntry.QuizQuestion q, int target) {
        if (q == null) return false;
        if (q.options == null || q.options.size() < 2) return false;

        // Ensure exactly 4 options (pad if needed)
        while (q.options.size() < 4) q.options.add("Option " + (q.options.size() + 1));
        if (q.options.size() > 4) q.options = new ArrayList<>(q.options.subList(0, 4));

        Integer idx = (q.correctIndex != null) ? q.correctIndex : q.answer;
        if (idx == null || idx < 0 || idx >= q.options.size()) return false;

        // Normalize fields so both authoring and hunter code can read them
        q.correctIndex = idx;
        q.answer = idx;
        q.question = ensureQuestionMark(safeText(q.question));
        q.q = ensureQuestionMark(safeText(q.q != null ? q.q : q.question));

        out.add(q);
        return out.size() >= target;
    }

    private static void fillWithFallbacks(List<String> list, List<String> pool, int targetSize) {
        for (String p : pool) {
            if (list.size() >= targetSize) break;
            boolean dup = false;
            for (String s : list) if (s.equalsIgnoreCase(p)) { dup = true; break; }
            if (!dup) list.add(p);
        }
        int i = 1;
        while (list.size() < targetSize) list.add("Option " + (i++));
    }

    private static int shuffleAndFindIndex(List<String> opts, String correct) {
        Collections.shuffle(opts, new Random());
        int idx = 0;
        for (int i = 0; i < opts.size(); i++) {
            if (opts.get(i).equalsIgnoreCase(correct)) { idx = i; break; }
        }
        return idx;
    }

    /** Very light “proper noun phrase” extractor: sequences of Capitalized words / acronyms. */
    private static List<String> extractProperNounPhrases(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String[] toks = text.split("\\s+");
        StringBuilder cur = new StringBuilder();
        for (String tok : toks) {
            String w = stripPunct(tok);
            if (w.isEmpty()) continue;
            if (isProperToken(w)) {
                if (cur.length() > 0) cur.append(" ");
                cur.append(w);
            } else {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            }
        }
        if (cur.length() > 0) out.add(cur.toString());

        // Filter obvious stopwords / noise
        List<String> cleaned = new ArrayList<>();
        for (String s : out) {
            String low = s.toLowerCase(Locale.US);
            if (!STOPWORDS.contains(low) && s.length() >= 3) cleaned.add(s);
        }

        // Dedup (case-insensitive) while keeping order
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : cleaned) {
            boolean seen = false;
            for (String t : set) if (t.equalsIgnoreCase(s)) { seen = true; break; }
            if (!seen) set.add(s);
        }
        return new ArrayList<>(set);
    }

    private static boolean isProperToken(String w) {
        if (w.length() < 2) return false;
        char c0 = w.charAt(0);
        // Capitalized word or ALL CAPS (acronyms)
        return Character.isUpperCase(c0) || w.equals(w.toUpperCase(Locale.US));
    }

    private static String stripPunct(String s) {
        return s.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    private static String pickVerb(String s) {
        String low = s.toLowerCase(Locale.US);
        if (low.contains("constructed")) return "constructed";
        if (low.contains("renovated"))   return "renovated";
        if (low.contains("founded"))     return "founded";
        if (low.contains("established")) return "established";
        if (low.contains("inaugurated")) return "inaugurated";
        if (low.contains("opened"))      return "opened";
        return "built";
    }

    private static String ensureQuestionMark(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.endsWith("?") ? t : (t + "?");
    }

    private static String safeText(String s) { return (s == null) ? "" : s; }

    private QuizGenerator() {}
}
