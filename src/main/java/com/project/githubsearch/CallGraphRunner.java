package com.project.githubsearch;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.apache.bcel.classfile.ClassParser;

import gr.gousiosg.javacg.stat.JCallGraph;

public class CallGraphRunner {

	public static void main(String... args) {
//		Set<String> methodsCallingTarget = findMethodsCallingTarget("executeRootHandler",
//				"/Users/kanghongjin/Downloads/undertow-core-2.1.3.Final.jar");

//		Set<String> methodsCallingTarget = findMethodsCallingTarget(
//				"io.undertow.server.handlers.RequestLimit#handleRequest(io.undertow.server.HttpServerExchange,io.undertow.server.HttpHandler)",
//				"src/main/java/com/project/githubsearch/jars/rate-limit-latest.jar");

		Map<String, List<String>>  targetsCalledBy = new HashMap<>();
		Set<String> methodsCallingTarget = findMethodsCallingTarget(
//				"handleRequest",
//				"io.undertow.server.handlers.RequestLimit#handleRequest",
//				"htmlEscaper",
//				"com.google.common.html.HtmlEscapers#htmlEscaper()",
//				"net.lightbody.bmp.proxy.error.ProxyError:getHtml(java.lang.String)",
//				"getRequestReceiver",
				"URIUtils#extractHost",
//				"src/main/java/com/project/githubsearch/jars/rate-limit-latest.jar"
//				"src/main/java/com/project/githubsearch/jars/ratpack-groovy-latest.jar",
//				"src/main/java/com/project/githubsearch/jars/browsermob-core-latest.jar",
				"/Users/kanghongjin/Downloads/httpclient5-deps/httpclient5-5.0.3.original.jar",
				targetsCalledBy
				);
//				"/Users/kanghongjin/Downloads/guava-30.0-jre.jar");
		
//				"src/main/java/com/project/githubsearch/jars/framework-generator-latest.jar");
//				"src/main/java/com/project/githubsearch/jars/jboss-as-domain-http-interface-latest.jar");
		System.out.println("ans=");
		System.out.println(methodsCallingTarget);
		System.out.println("===");
		System.out.println(targetsCalledBy.get("org.apache.hc.client5.http.utils.URIUtils:extractHost(java.net.URI)"));
		System.out.println("done");
	}

	/**
	 * 
	 * @param targetMethod
	 * @param jarFilePath
	 * @param targetCalledBy  modified to take the slice of the call graph relevant to targetMethod
	 * @return
	 */
	public static Set<String> findMethodsCallingTarget(String targetMethod, String jarFilePath, Map<String, List<String>> targetCalledBy) {
		System.out.println("========");
		if (targetMethod.contains("#")) {
			targetMethod = targetMethod.replace("#", ":");
		}
		System.out.println("Getting methods for target : " + targetMethod + ". In jar file: " + jarFilePath);
		
		// get the output of java-callgraph
		String callGraphOutput = extractCallGraph(jarFilePath);
//		System.out.println(callGraphOutput);
		System.out.println("===");

		Map<String, List<String>> calls = new HashMap<>();
		Map<String, List<String>> calledBy = new HashMap<>();

		// additional info.
		Map<String, List<String>> constructors = new HashMap<>();

		extractCallGraphInformation(callGraphOutput, calls, calledBy, constructors);

		String fullySpecifiedTargetMethod = getFQN(targetMethod, jarFilePath, calls, calledBy);

		if (fullySpecifiedTargetMethod == null) {
			throw new RuntimeException("cannot find target method. ");
		}

		if (!calledBy.containsKey(fullySpecifiedTargetMethod)) {
			// the call graph doesn't contain our method
			System.out.println("early return as the call graph does not contain the method 	" + fullySpecifiedTargetMethod);
			Set<String> defaultResponse = new HashSet<>();
			defaultResponse.add(fullySpecifiedTargetMethod);
			return defaultResponse;
			
		}

		Set<String> visited = new HashSet<>();
		List<String> workList = new ArrayList<>();
		workList.add(fullySpecifiedTargetMethod);
		while (!workList.isEmpty()) {
			String currentMethod = workList.remove(0);
			visited.add(currentMethod);
			if (!targetCalledBy.containsKey(currentMethod)) {
				targetCalledBy.put(currentMethod, new ArrayList<>());
			}

			List<String> nextItems = calledBy.get(currentMethod);
			if (nextItems == null) {
				continue;
			}

			for (String nextItem : nextItems) {
				
				nextItem = nextItem.trim();
				
				if (!targetCalledBy.get(currentMethod).contains(nextItem)) { 
					targetCalledBy.get(currentMethod)
									.add(nextItem);
					System.out.println("updating calledBy : " + currentMethod + " <-> " + nextItem);
				}
				if (visited.contains(nextItem)) {
					continue;
				}
				workList.add(nextItem);

				if (nextItem.split(":")[0].contains("$")) { // anonymous class/ lambdas ... Look at where their constructors are instead
					String nextItemClass = nextItem.split(":")[0].trim();

					if (constructors.containsKey(nextItemClass)) {
						List<String> unvisitedCtors = constructors.get(nextItemClass)
								.stream()
								.filter(ctor -> !visited.contains(ctor))
								.collect(Collectors.toList());
						workList.addAll(unvisitedCtors);
						
						// kinda... the ctor of an anonymous class "calls" the "execute" methods and whatever 
						if (!targetCalledBy.containsKey(nextItem)) {
							targetCalledBy.put(nextItem, new ArrayList<>());	
						}
						targetCalledBy.get(nextItem).addAll(unvisitedCtors);
					}
				}

			}
		}
		
		System.out.println("========");

		// `visited` is what we want
		// but anonymous classes can't be called, so let's not care about them
		return visited.stream()
				.filter(method -> !method.contains("$"))
				.map(method -> method.trim())
				.collect(Collectors.toSet());

	}

	private static String extractCallGraphInformation(String callGraphOutput,
			Map<String, List<String>> calls, Map<String, List<String>> calledBy,
			Map<String, List<String>> constructors) {
		String fullySpecifiedTargetMethod = null;
		for (String line : callGraphOutput.split("\n")) {
			if (line.contains("M:")) {

				String method1 = line.split("M:")[1].split("\\([MIOSD]\\)")[0].trim();
				String method2 = line.split("\\([MIOSD]\\)")[1].trim();

				if (!calls.containsKey(method1)) {
					calls.put(method1, new ArrayList<>());
				}
				if (!calledBy.containsKey(method2)) {
					calledBy.put(method2, new ArrayList<>());
				}
				calls.get(method1).add(method2);
				calledBy.get(method2).add(method1);

				
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
			String classOfMethod1 = method1.split(":<init")[0].trim();
			if (!constructors.containsKey(classOfMethod1)) {
				constructors.put(classOfMethod1, new ArrayList<>());
			}
			constructors.get(classOfMethod1).add(method1.trim());
		}
	}

	public static String getFQN(String partialName, String jarFile, Map<String, List<String>> calls, Map<String, List<String>> calledBy) {
		Set<String> candidates = new HashSet<>();
		try {
			List<String> allNames = getMethods(jarFile);
			for (String name : allNames) {
				if (name.contains(partialName)) {
					candidates.add(name.trim());
				}
			}
		} catch (IOException e) {
			
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		for (String method1 : calls.keySet()) {
			if (method1.contains(partialName)) {
				candidates.add(method1.trim());
			} 
		}
		for (String method1 : calledBy.keySet()) {
			if (method1.contains(partialName)) {
				candidates.add(method1.trim());
			} 
		}
		
		if (candidates.size() != 1) {
			// if > 1, the partial name is underspecified
			// if < 1, no match 
			throw new RuntimeException("Couldnt find fully specified name somehow. candidates=" + candidates);
		}
		System.out.println("FQ name = " + candidates.iterator().next());
		
		return candidates.iterator().next();
		
	}
	
	private static List<String> getMethods(String f) throws IOException {
		List<String> methodNames = new ArrayList<>();
		try (JarFile jar = new JarFile(f)) {
			Enumeration<JarEntry> jarEntries = jar.entries();
			for (JarEntry entry = jarEntries.nextElement(); jarEntries.hasMoreElements(); entry = jarEntries.nextElement()) {
				if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
					continue;
				}
					
				ClassParser cp = new ClassParser(f, entry.getName());
				methodNames.addAll(new CallGraphClassVisitor(cp.parse()).getMethods());
			}
		}

//		System.out.println(methodNames);
		return methodNames;
	}
}
