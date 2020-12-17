package com.project.githubsearch;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.kevinsawicki.http.HttpRequest;
import com.project.githubsearch.model.MavenPackage;
import com.project.githubsearch.model.Query;
import com.project.githubsearch.model.ResolvedFiles;
import com.project.githubsearch.model.ResolvedFile;
import com.project.githubsearch.model.Response;
import com.project.githubsearch.model.SynchronizedFeeder;
import com.project.githubsearch.model.SynchronizedTypeSolver;
import com.project.githubsearch.SourceCodeAcceptor.RejectReason;
import com.project.githubsearch.model.GithubToken;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

/**
 * Clone of App.java
 *
 */
public class CallGraphAcrossProjects {

	public final static boolean debug = false;

	public static void main(String[] args) throws IOException {

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

		getToWork(args, additionalKeywordConstraints, negativeKeywordConstraints, workList, functionToJars);

	}

	private static void getToWork(String[] args, List<String> additionalKeywordConstraints,
			List<String> negativeKeywordConstraints, List<String> workList,
			Map<String, Set<String>> functionToJars) throws IOException {
		
		int i = 0;
		Set<String> visited = new HashSet<>();
		while (!workList.isEmpty()) {
			i += 1;
			if (i > 30) {
				break;
			}
			String name = workList.remove(0);
			visited.add(name);
			for (String jarFile : functionToJars.get(name)) {

				Set<String> inputs = CallGraphRunner.findMethodsCallingTarget(name, jarFile);

				// transform call graph outputs into the format expected by ausearch
				inputs = inputs.stream().map(input -> input.replace(":", "#")).collect(Collectors.toSet());

				System.out.println("searches will be on " + inputs);

				// then search usage of each method
				for (String input : inputs.stream().limit(7).collect(Collectors.toList())) {
					prepareSearch(args, additionalKeywordConstraints, input);
					Set<String> filePaths = new HashSet<>(App.runSearch(input, true, additionalKeywordConstraints,
							negativeKeywordConstraints, 5, 1970, false));

					System.out.println();
					System.out.println();
					System.out.println("getting jars?");
					System.out.println("filespthat = " + filePaths);
					System.out.println();
					functionToJars.put(input, new HashSet<>());
					for (String filePath : filePaths) {
						String jar = JarRetriever.getLikelyJarOf(new File(filePath));

						System.out.println("for " + input + ", got jar " + jar);
						functionToJars.get(input).add(jar);
						
						if (!visited.contains(input)) {
							workList.add(input);
						}
					}
				}
			}
		}
	}

	private static void prepareSearch(String[] args, List<String> additionalKeywordConstraints, String input)
			throws IOException {
		// nastiness... because DATA_LOCATION in App is not constant
		App.reset();
		App.isRareApi = true;
		App.synchronizedFeeder = new SynchronizedFeeder(args[2].split(","));

		Query query = App.parseQuery(input, additionalKeywordConstraints, false);
		String nameOfFolder = App.nameOfFolder(query, true);

		if (new File(App.DATA_LOCATION + nameOfFolder).exists()) {
			System.out.println("deleting...");
			Files.walk(new File(App.DATA_LOCATION + nameOfFolder).toPath()).sorted(Comparator.reverseOrder())
					.map(Path::toFile).forEach(File::delete);
		}
		// nastiness ends
	}

}
