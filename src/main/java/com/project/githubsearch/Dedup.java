package com.project.githubsearch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Let's do heuristic based clone detection. Similar to Sourcerer's CC
 * 
 * 
 * @author kanghongjin
 *
 */
public class Dedup {
	
	// store id -> collection of tokens appearing
	Map<Integer, Set<String>> canonicalCopies = new HashMap<>();
	// store id -> number of times a clone is detected
	Map<Integer, Integer> canonicalCopiesCount = new HashMap<>();
	
	// threshold to be considered as clones
	public final float threshold = 0.9f;
	
	public List<String> stripComments(List<String> lines) {
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
	
	

}
