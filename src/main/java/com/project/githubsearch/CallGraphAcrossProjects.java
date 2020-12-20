package com.project.githubsearch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.project.githubsearch.model.Query;
import com.project.githubsearch.model.SynchronizedFeeder;

/**
 * Clone of App.java
 *
 */
public class CallGraphAcrossProjects {

	public final static boolean debug = false;

	public static void main(String[] args) throws IOException {

		int numberToRetrieve = Integer.parseInt(args[1]);
		
		List<String> additionalKeywordConstraints = new ArrayList<>();
		List<String> negativeKeywordConstraints = new ArrayList<>();

		String jarFile = "";
		if (args.length > 3) {
			// args[5] and beyond
			for (int i = 3; i < args.length; i++) {
				if (args[i].startsWith("--plus=")) {
					String additionalKeywordsCommaSeparated = args[i].split("--plus=")[1];

					additionalKeywordConstraints = Arrays.asList(additionalKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--not=")) {

					String negativelKeywordsCommaSeparated = args[i].split("--not=")[1];
					negativeKeywordConstraints = Arrays.asList(negativelKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--jar=")) {
					jarFile = args[i].split("--jar=")[1];
				}
			}
		}

		// first traverse call graph and get all interesting methods
		String simplifiedMethodName = args[0].replace("#", ":").split("\\(")[0];

		List<String> workList = new ArrayList<>();
		workList.add(simplifiedMethodName);
		Map<String, Set<String>> functionToJars = new HashMap<>(); // gives the jars that use a function
		functionToJars.put(simplifiedMethodName, new HashSet<>());
		functionToJars.get(simplifiedMethodName).add(jarFile);

		getToWork(args[2].split(",")[0], numberToRetrieve, additionalKeywordConstraints, negativeKeywordConstraints, workList, functionToJars);

	}

	private static void getToWork(String token, int numberToRetrieve, List<String> additionalKeywordConstraints,
			List<String> negativeKeywordConstraints, List<String> workList,
			Map<String, Set<String>> functionToJars) throws IOException {
		
		int i = 0;
		Set<String> visited = new HashSet<>();
		Map<String, List<String>> targetCalledBy = new HashMap<>(); // this tracks the callgraph of `visited` across multiple projects
		
		while (!workList.isEmpty()) {
			i += 1;
			if (i > 50) {
				System.out.println("ending worklist!");
				break;
			}
			String name = workList.remove(0);
			System.out.println("WorkList: removing " + name);
			
			for (String jarFile : functionToJars.get(name)) {

				Set<String> inputs;
				try {
					
					inputs = CallGraphRunner.findMethodsCallingTarget(name, jarFile, targetCalledBy);
				} catch (Exception e) {
					e.printStackTrace();
					System.out.println("Skipping! Cannot find target method somehow!" + name + " ... in jarfile: " + jarFile);
					continue;
				}

				// transform call graph outputs into the format expected by AUSearch
				inputs = inputs.stream().map(input -> input.replace(":", "#").trim()).collect(Collectors.toSet());

				System.out.println("searches will be on " + inputs);
				
				// then search usage of each method
				for (String input : inputs.stream().limit(10).collect(Collectors.toList())) {
					if (visited.contains(input)) {
						continue; // searches are expensive. Skip them.
					}
					prepareSearch(token, numberToRetrieve, additionalKeywordConstraints, input);
					Set<String> filePaths = new HashSet<>(App.runSearch(input, true, additionalKeywordConstraints,
							negativeKeywordConstraints, 10, 1970, false));
					

					System.out.println();
					System.out.println();
					System.out.println("getting jars?");
					System.out.println("file paths = " + filePaths);
					System.out.println();
					functionToJars.put(input, new HashSet<>());
					for (String filePath : filePaths) {
						String jar = JarRetriever.getLikelyJarOf(new File(filePath));

						System.out.println("for " + input + ", got jar " + jar);

						if (!visited.contains(input)) {
							System.out.println("WorkList: adding " + input);
							workList.add(input);
							visited.add(input); // this is needed so we don't add the same thing to worklist so many times
						}
						
						if (jar == null) {
							continue;
						}
						functionToJars.get(input).add(jar);
					}
					visited.add(input); // needed if filePaths is empty
				}
			}
			visited.add(name);
		}
		
		System.out.println("ended. Done");
		System.out.println(visited); // need to store more info like which project?
		
		// print edges first. FOr debugging
		for (Map.Entry<String, List<String>> entry : targetCalledBy.entrySet()) {
			System.out.println(entry.getKey());
			for (String item : entry.getValue()) {
				System.out.println("\t -> " + item);
				
			}
			System.out.println();
		}
		// 
	}

	private static void prepareSearch(String token, int numberToRetrieve, List<String> additionalKeywordConstraints, String input)
			throws IOException {
		// nastiness... because DATA_LOCATION in App is not constant
		App.reset();
		App.isRareApi = true;
		App.synchronizedFeeder = new SynchronizedFeeder(new String[] {token});

		Query query = App.parseQuery(input, additionalKeywordConstraints, false);
		String nameOfFolder = App.nameOfFolder(query, true);

		if (new File(App.DATA_LOCATION + nameOfFolder).exists()) {
			System.out.println("deleting...");
			Files.walk(new File(App.DATA_LOCATION + nameOfFolder).toPath()).sorted(Comparator.reverseOrder())
					.map(Path::toFile).forEach(File::delete);
		}
		
		App.MAX_RESULT = numberToRetrieve; // not exactly. This is the number of unique candidates that is wanted
		App.MAX_TO_INSPECT = App.MAX_RESULT * 30; // can't keep checking forever, we stop after looking at MAX_TO_INSPECT files

		System.out.println("Maximum files to inspect=" + App.MAX_TO_INSPECT);

		// nastiness ends
	}

}
