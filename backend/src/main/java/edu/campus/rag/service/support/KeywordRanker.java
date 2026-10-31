package edu.campus.rag.service.support;

import edu.campus.rag.entity.Chunk;
import java.util.*;
import java.util.regex.Pattern;

/** Small-corpus BM25: Chinese bigrams and complete Latin/number terms. No external service. */
public final class KeywordRanker {
  private KeywordRanker() {}
  public record Hit(Chunk chunk, double score) {}
  private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+");
  private static final Set<String> STOP = Set.of("什么", "哪些", "怎么", "如何", "是否", "可以", "需要", "请问", "规定", "学生");

  static List<String> terms(String text) {
    var terms = new ArrayList<String>();
    var matcher = WORDS.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String word = matcher.group();
      int[] cps = word.codePoints().toArray();
      if (Character.UnicodeScript.of(cps[0]) == Character.UnicodeScript.HAN) {
        for (int i = 0; i + 1 < cps.length; i++) {
          String term = new String(cps, i, 2);
          if (!STOP.contains(term)) terms.add(term);
        }
      } else terms.add(word);
    }
    return terms;
  }

  public static List<Hit> search(String query, List<Chunk> corpus, int limit) {
    var queryTerms = new HashSet<>(terms(query));
    if (queryTerms.isEmpty() || corpus.isEmpty()) return List.of();
    var frequencies = new ArrayList<Map<String, Integer>>();
    var lengths = new ArrayList<Integer>();
    var documentFrequency = new HashMap<String, Integer>();
    for (var chunk : corpus) {
      var tokens = terms(Optional.ofNullable(chunk.sectionLabel).orElse("") + " " + chunk.chunkText);
      var counts = new HashMap<String, Integer>();
      tokens.forEach(t -> counts.merge(t, 1, Integer::sum));
      queryTerms.stream().filter(counts::containsKey).forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
      frequencies.add(counts);
      lengths.add(tokens.size());
    }
    double average = lengths.stream().mapToInt(Integer::intValue).average().orElse(1);
    var hits = new ArrayList<Hit>();
    for (int i = 0; i < corpus.size(); i++) {
      double score = 0;
      for (String term : queryTerms) {
        int frequency = frequencies.get(i).getOrDefault(term, 0);
        if (frequency == 0) continue;
        int df = documentFrequency.get(term);
        double idf = Math.log(1 + (corpus.size() - df + .5) / (df + .5));
        score += idf * frequency * 2.2 / (frequency + 1.2 * (.25 + .75 * lengths.get(i) / average));
      }
      if (score > 0) hits.add(new Hit(corpus.get(i), score));
    }
    return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()
        .thenComparing(h -> h.chunk().id)).limit(limit).toList();
  }
}
