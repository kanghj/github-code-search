package com.project.githubsearch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

/**
 * Let's do heuristic based clone detection. Similar to Sourcerer's CC
 * This class throws out source code that doesn ot meet the criteria of acceptance, including not being a clone of existing accepted programs.
 * Metadata is also trakced and updated by this class. It is very stateful.
 * 
 * @author kanghongjin
 *
 */
public class SourceCodeAcceptor {
	
	public enum RejectReason {
		CLONE, NEGATIVE_KEYWORD,
	}
	
	// store id -> collection of tokens appearing
	static Map<Integer, Set<String>> canonicalCopies = new HashMap<>();
	static Map<Integer, Set<String>> canonicalCopiesUrl = new HashMap<>();
	// store id -> number of times a clone is detected
	static Map<Integer, Integer> canonicalCopiesCount = new HashMap<>();
	static Map<Integer, Boolean> canonicalCopiesResolvable = new HashMap<>();
	static int resolvable = 0;
	
	// threshold to be considered as clones
	public static final float threshold = 0.8f;
	
	public static List<String> stripComments(List<String> lines) {
		// we know its java
		
		return lines.stream()
					// strip lines that are just comments
					.filter(line -> !line.trim().startsWith("//"))
					.filter(line -> !line.trim().startsWith("/*"))
					.filter(line -> !line.trim().startsWith("* "))
					.filter(line -> !line.trim().startsWith("*/"))
					// then modify each line, removing the comments at the back
					.map(line -> line.split("////")[0])
					.collect(Collectors.toList());
		
	}
	
	public static void indicateCanBeResolved(int id) {
		canonicalCopiesResolvable.put(id, true);
		resolvable += 1;
	}
	
	public static float intersectionRatio(Set<String> canonicalCopy, Set<String> tokens) {
		Set<String> intersection = new HashSet<>();
		intersection.addAll(canonicalCopy);
		intersection.retainAll(tokens);
		
		int numerator = intersection.size();
		float denominator = Math.min(canonicalCopy.size(), tokens.size());
		return numerator / denominator;
	}
	
	/**
	 * Detect if is a clones of a previously seen piece of code.
	 * starsOnRepo is updated to reflect the highest number of stars received by any clone of the canonical copy or itself.
	 * @param id
	 * @param url
	 * @param linesOfCode
	 * @param stars
	 * @param starsOnRepo
	 * @param negativeKeywordConstraint 
	 * @return
	 */
	public static Optional<RejectReason> accept(int id, String url, List<String> linesOfCode, int stars, Map<Integer, Integer> starsOnRepo, List<String> negativeKeywordConstraint) {
		linesOfCode = stripComments(linesOfCode);
		
		String codeAsStr = String.join(" ", linesOfCode);
		
		Set<String> tokens = new HashSet<>(Arrays.asList(codeAsStr.split(" ")));
		
		if (tokens.stream().anyMatch(token -> negativeKeywordConstraint.contains(token))) {
			return Optional.of(RejectReason.NEGATIVE_KEYWORD);
		}
		
		for (Entry<Integer, Set<String>> canonicalCopy : canonicalCopies.entrySet()) {
			Integer key = canonicalCopy.getKey();
			float intersectionRatio = intersectionRatio(canonicalCopy.getValue(), tokens);
			if (intersectionRatio > threshold) {				
				canonicalCopiesCount.put(key, canonicalCopiesCount.get(key) + 1);
				canonicalCopiesUrl.get(key).add(url);
				
				starsOnRepo.put(key, Math.max(starsOnRepo.get(key), stars));
				return Optional.of(RejectReason.CLONE);
			}
		}
		
		// new copy; not a clone of anything we've seen so far
		canonicalCopies.put(id, tokens);
		canonicalCopiesCount.put(id, 1);
		canonicalCopiesUrl.put(id, new HashSet<>());
		canonicalCopiesUrl.get(id).add(url);
		canonicalCopiesResolvable.put(id, false); // don't know yet
		
		starsOnRepo.put(id, stars);
		return Optional.empty();
	}
	

}
