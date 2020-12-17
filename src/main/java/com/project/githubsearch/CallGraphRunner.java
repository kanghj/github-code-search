package com.project.githubsearch;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import gr.gousiosg.javacg.stat.JCallGraph;

public class CallGraphRunner {

	public static void main(String... args) {
		Set<String> methodsCallingTarget = findMethodsCallingTarget("executeRootHandler",
				"/Users/kanghongjin/Downloads/undertow-core-2.1.3.Final.jar");

		System.out.println(methodsCallingTarget);
		System.out.println("done");
	}

	public static Set<String> findMethodsCallingTarget(String targetMethod, String jarFilePath) {
		System.out.println("getting thing :" + targetMethod + " ... " + jarFilePath);
		// get the output of java-callgraph
		String callGraphOutput = extractCallGraph(jarFilePath);

		Map<String, List<String>> calls = new HashMap<>();
		Map<String, List<String>> calledBy = new HashMap<>();

		// additional info.
		Map<String, List<String>> constructors = new HashMap<>();

		String fullySpecifiedTargetMethod = null;

		fullySpecifiedTargetMethod = extractCallGraphInformation(targetMethod, callGraphOutput, calls, calledBy,
				constructors, fullySpecifiedTargetMethod);

		if (fullySpecifiedTargetMethod == null) {
			throw new RuntimeException("cannot find target method. ");
		}

		if (!calledBy.containsKey(fullySpecifiedTargetMethod)) {
			// the call graph doesn't contain our method
			return new HashSet<>();
		}

		Set<String> visited = new HashSet<>();
		List<String> workList = new ArrayList<>();
		workList.add(fullySpecifiedTargetMethod);
		while (!workList.isEmpty()) {
			String currentMethod = workList.remove(0);
			visited.add(currentMethod);

			List<String> nextItems = calledBy.get(currentMethod);
			if (nextItems == null) {
				continue;
			}

			for (String nextItem : nextItems) {
				if (visited.contains(nextItem)) {
					continue;
				}
				workList.add(nextItem);

				if (nextItem.contains("$")) { // anonymous class/ lambdas etc...
					String nextItemClass = nextItem.split(":")[0];

					if (constructors.containsKey(nextItemClass)) {
						workList.addAll(constructors.get(nextItemClass).stream().filter(ctor -> !visited.contains(ctor))
								.collect(Collectors.toList()));
					}
				}

			}
		}

		// visited is what we want
		// but anonymous classes can't be called, so let's not care about them

		return visited.stream().filter(method -> !method.contains("$")).map(method -> method.trim())
				.collect(Collectors.toSet());

	}

	private static String extractCallGraphInformation(String targetMethod, String callGraphOutput,
			Map<String, List<String>> calls, Map<String, List<String>> calledBy, Map<String, List<String>> constructors,
			String fullySpecifiedTargetMethod) {
		for (String line : callGraphOutput.split("\n")) {
			if (line.contains("M:")) {

				String method1 = line.split("M:")[1].split("\\([MIOSD]\\)")[0];
				String method2 = line.split("\\([MIOSD]\\)")[1];

				if (!calls.containsKey(method1)) {
					calls.put(method1, new ArrayList<>());
				}
				if (!calledBy.containsKey(method2)) {
					calledBy.put(method2, new ArrayList<>());
				}
				calls.get(method1).add(method2);
				calledBy.get(method2).add(method1);

				// additional work #1
				// identify fully spcified name
				if (method1.contains(targetMethod)) {
					fullySpecifiedTargetMethod = method1;
				} else if (method2.contains(targetMethod)) {
					fullySpecifiedTargetMethod = method2;
				}

				// additional work #2
				// track constructors
				// this information is useful later, because if an anonymous class calls a
				// target method, we need to know who invokes its constructor.
				addToConstructors(constructors, method1);
				addToConstructors(constructors, method2);

			}
		}
		return fullySpecifiedTargetMethod;
	}

	private static String extractCallGraph(String jarFilePath) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		System.setOut(new PrintStream(baos));

		JCallGraph.main(new String[] { jarFilePath });

		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
		String callGraphOutput = baos.toString();
		return callGraphOutput;
	}

	private static void addToConstructors(Map<String, List<String>> constructors, String method1) {
		if (method1.contains(":<init>")) {
			String classOfMethod1 = method1.split(":<init")[0];
			if (!constructors.containsKey(classOfMethod1)) {
				constructors.put(classOfMethod1, new ArrayList<>());
			}
			constructors.get(classOfMethod1).add(method1);
		}
	}
}
