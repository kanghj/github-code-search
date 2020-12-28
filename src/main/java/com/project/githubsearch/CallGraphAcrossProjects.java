package com.project.githubsearch;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
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
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
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

		// TODO awkward API here
		Map<String, Set<String>> functionToJars = new HashMap<>(); // tracks the jars that use a function
		functionToJars.put(simplifiedMethodName, new HashSet<>());
		functionToJars.get(simplifiedMethodName).add(jarFile);

		getToWork(args[2].split(",")[0], numberToRetrieve, additionalKeywordConstraints, negativeKeywordConstraints,
				simplifiedMethodName, functionToJars);

	}

	private static void getToWork(String token, int numberToRetrieve, List<String> additionalKeywordConstraints,
			List<String> negativeKeywordConstraints, String simplifiedMethodName,
			Map<String, Set<String>> jarsCallingFunction) throws IOException {

		List<String> workList = new ArrayList<>();
		workList.add(simplifiedMethodName);

		Map<String, String> callgraphNodeToContainingJar = new HashMap<>();
		// different from jarsCallingFunction
		// callgraphNodeToContainingJar maps a function to the JAR File that contains it
		// jarsCallingFunction maps a function to the JAR files that may be using the
		// function

		int i = 0;
		Set<String> visited = new HashSet<>();
		Map<String, List<String>> targetCalledBy = new HashMap<>(); // this tracks the callgraph of `visited` across
																	// multiple projects

		while (!workList.isEmpty()) {
			i += 1;
			if (i > 50) {
				System.out.println("ending worklist!");
				break;
			}
			String name = workList.remove(0);
			System.out.println();
			System.out.println("WorkList: removing " + name);

			for (String jarFile : jarsCallingFunction.get(name)) {

				Set<String> inputs;
				try {
					inputs = CallGraphRunner.findMethodsCallingTarget(name, jarFile, targetCalledBy);
					for (String input : inputs) {
						callgraphNodeToContainingJar.put(input,
								// trim off the obvious stuff
								jarFile.contains("src/main/java/com/project/githubsearch/jars/") ? jarFile.trim().split("src/main/java/com/project/githubsearch/jars/")[1] : jarFile
						);
					}
				} catch (Exception e) {
					e.printStackTrace();
					System.out.println(
							"Skipping! Cannot find target method somehow!" + name + " ... in jarfile: " + jarFile);
					continue;
				}

				// transform call graph outputs into the format expected by AUSearch
				inputs = inputs.stream().map(input -> input.replace(":", "#").trim()).collect(Collectors.toSet());

				System.out.println("searches will be on " + inputs);

				// then search usage of each method
				for (String input : inputs.stream()
						.limit(30)
						.collect(Collectors.toList())) {
					
					if (visited.contains(input)) {
						continue; // searches are expensive. Do not run search for the same thing twice.
					}
					prepareSearch(token, numberToRetrieve, additionalKeywordConstraints, input);
					Set<String> filePaths = new HashSet<>(App.runSearch(input, true, additionalKeywordConstraints,
							negativeKeywordConstraints, 10, 1970, false));

					System.out.println();
					System.out.println();
					System.out.println("getting jars?");
					System.out.println("file paths = " + filePaths);
					System.out.println();
					jarsCallingFunction.put(input, new HashSet<>());
					for (String filePath : filePaths) {
						String jar = JarRetriever.getLikelyJarOf(new File(filePath));

						System.out.println("for " + input + ", adding jar " + jar);

						if (!visited.contains(input)) {
							System.out.println("WorkList: adding " + input);
							workList.add(input);
							visited.add(input); // so we don't add the same thing to worklist so many
												// times
						}

						if (jar == null) {
							continue;
						}
						jarsCallingFunction.get(input).add(jar);
					}
					visited.add(input); // needed if filePaths is empty
				}
			}
			visited.add(name);
		}

		System.out.println("ended. Done");
		System.out.println(visited); // need to store more info like which project?

		System.out.println("call graph resembles what is shown below:");
		// print edges first. For debugging
		for (Map.Entry<String, List<String>> entry : targetCalledBy.entrySet()) {
			System.out.println(entry.getKey());
			for (String item : entry.getValue()) {
				System.out.println("\t -> " + item);

			}
			System.out.println();
		}
		//

		// write all possible 'chains' to an output file
		writeCallChains(targetCalledBy, simplifiedMethodName, callgraphNodeToContainingJar);
	}

	private static void writeCallChains(Map<String, List<String>> targetCalledBy, String filename,
			Map<String, String> functionToJars) {

		Set<String> notCalledByAnything = new HashSet<>();
		for (Entry<String, List<String>> entry : targetCalledBy.entrySet()) {
			if (entry.getValue().isEmpty()) {
				notCalledByAnything.add(entry.getKey());
			}
		}

		List<List<String>> workList = new ArrayList<>();
		for (String item : notCalledByAnything) {
			workList.add(Arrays.asList(item.trim()));
		}

		Map<String, List<String>> targetCalls = new HashMap<>();
		for (Entry<String, List<String>> entry : targetCalledBy.entrySet()) {
			for (String item : entry.getValue()) {
				if (!targetCalls.containsKey(item)) {
					targetCalls.put(item, new ArrayList<>());
				}
				targetCalls.get(item).add(entry.getKey());
			}
		}

		List<List<String>> outputCallChains = new ArrayList<>();

		while (!workList.isEmpty()) {
			List<String> items = workList.remove(0);
//			System.out.println("looking at " + items);
			if (items.size() > 25) {
				// probably, we messed up somewhere
				System.out.println(
						"call chain is >25 items. Probably messed up somewhere. Will not continue on this chain to prevent infinite loops");
				outputCallChains.add(items); // just add this long chain as an answer.
				continue;
			}

			String mostRecentItem = items.get(items.size() - 1);
			List<String> nexts = targetCalls.get(mostRecentItem);
			
			nexts = nexts.stream()
					.filter(next -> !mostRecentItem.equals(next)) // prevent recursion on itself
					.collect(Collectors.toList());
			
			if (nexts == null || nexts.isEmpty()) {
				outputCallChains.add(items);
			} else {
				for (String next : nexts) {
					List<String> nextItems = new ArrayList<>(items);
					nextItems.add(next.trim());
					workList.add(nextItems);
				}
			}
		}

		System.out.println("write to call_chains_" + filename + ".txt");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter("call_chains_" + filename + ".txt"))) {
			for (List<String> outputLine : outputCallChains) {
				List<String> outputLineWithJar = outputLine.stream()
						.map(item -> item + "@@" + functionToJars.get(item))
						.collect(Collectors.toList());
				writer.write(String.join(";", Lists.reverse(outputLineWithJar)));
				writer.write("\n");
				writer.write("\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Unable to write call chains ...");
		}

		// call chains with jar file
	}

	private static void prepareSearch(String token, int numberToRetrieve, List<String> additionalKeywordConstraints,
			String input) throws IOException {
		// nastiness... because DATA_LOCATION in App is not constant
		App.reset();
		App.isRareApi = true;
		App.synchronizedFeeder = new SynchronizedFeeder(new String[] { token });

		Query query = App.parseQuery(input, additionalKeywordConstraints, false);
		String nameOfFolder = App.nameOfFolder(query, true);

		if (new File(App.DATA_LOCATION + nameOfFolder).exists()) {
			System.out.println("deleting...");
			Files.walk(new File(App.DATA_LOCATION + nameOfFolder).toPath()).sorted(Comparator.reverseOrder())
					.map(Path::toFile).forEach(File::delete);
		}

		App.MAX_RESULT = numberToRetrieve; // not exactly. This is the number of unique candidates that is wanted
		App.MAX_TO_INSPECT = App.MAX_RESULT * 25; // can't keep checking forever, we stop after looking at
													// MAX_TO_INSPECT files

		System.out.println("Maximum files to inspect=" + App.MAX_TO_INSPECT);

		// nastiness ends
	}

}
