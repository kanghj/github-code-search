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
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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

	// number of needed file to be resolved
	private static int MAX_RESULT = 20; 
	private static int MAX_TO_INSPECT = 50_000; // should increase this number?

	// folder location to save the downloaded files and jars
	// HJ notes : these are not actually constants....
	private static String DATA_LOCATION = "src/main/java/com/project/githubsearch/data/";
	private static String DATA_LOCATION_FAILED = "src/main/java/com/project/githubsearch/failed_data/";
	private static final String JARS_LOCATION = "src/main/java/com/project/githubsearch/jars/";

	private static final String endpoint = "https://api.github.com/search/code";

	private static SynchronizedFeeder synchronizedFeeder;
	private static ResolvedFiles resolvedFiles = new ResolvedFiles();
	private static SynchronizedTypeSolver synchronizedTypeSolver = new SynchronizedTypeSolver();

	private static Instant start;

	
	private static Map<Integer, Integer> starsOnRepo = new HashMap<>();
	
	public final static boolean debug = false;

	public static void main(String[] args) {
		System.out.println("args: " + Arrays.toString(args));
		String input = args[0];
		
		int numberToRetrieve = Integer.parseInt(args[1]);
		MAX_RESULT = numberToRetrieve; // not exactly. This is the number of unique candidate-usage that is wanted
		MAX_TO_INSPECT = MAX_RESULT * 10; 
		
		System.out.println("Maximum files to inspect=" + MAX_TO_INSPECT);

		// token
		synchronizedFeeder = new SynchronizedFeeder(args[2].split(","));
		
		boolean isPartitionedBySize = true;  // true if we want to split up the queries by size
		
		List<String> additionalKeywordConstraints = new ArrayList<>();
		List<String> negativeKeywordConstraints = new ArrayList<>();

		int minStars = -1;
		int updatedAfterYear = 1970;
		boolean isNotApi = false;
		
		if (args.length > 3) {
			// args[5] and beyond
			for (int i = 3 ; i < args.length; i++) {
				if (!args[i].startsWith("--")) {
					String additionalKeywordsCommaSeparated = args[i];
					
					additionalKeywordConstraints = Arrays.asList(additionalKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--not=")) {
					
					String negativelKeywordsCommaSeparated = args[i].split("--not=")[1];
					negativeKeywordConstraints = Arrays.asList(negativelKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--star=")) {
					try {
						minStars = Integer.parseInt(args[i].split("--star=")[1]);
					} catch (NumberFormatException e) {
						throw new RuntimeException("invalid --star value. You gave " + args[i] + ", which could not be parsed and caused a NumberFormatException");
					}
				} else if (args[i].startsWith("--api=")) {
					isNotApi = !Boolean.parseBoolean(args[i].split("--api=")[1]);
				} else if (args[i].startsWith("--size=")) {
					isPartitionedBySize = Boolean.parseBoolean(args[i].split("--size=")[1]);
				} 
			}
		}

		System.out.println("You are searching for " + OutputUtils.ANSI_PURPLE + input + OutputUtils.ANSI_RESET);
		System.out.println("The search space will " + OutputUtils.ANSI_BLUE   + (isPartitionedBySize ? "" : "NOT ") + "be partitioned by size " + OutputUtils.ANSI_RESET + "(required for scaling up beyond GitHub's search result limits)");
		if (isNotApi) {
			System.out.println("Types will NOT" + OutputUtils.ANSI_BLUE + " be resolved (--api=false)" + OutputUtils.ANSI_RESET );
		}
		if (minStars > 0) {
			System.out.println("There is a minimum star threshold that the repo must exceed (" + minStars + ")");
		}
		
		if (additionalKeywordConstraints.size() > 0 || negativeKeywordConstraints.size() > 0) {
			System.out.println("Other keywords to pass to GitHub:");
			System.out.println(OutputUtils.ANSI_BLUE + "additional (will be biased towards results with these words):" + OutputUtils.ANSI_RESET + String.join(",", additionalKeywordConstraints));
			System.out.println(OutputUtils.ANSI_BLUE + "negative (will completely filter results with these words):" + OutputUtils.ANSI_RESET + String.join(",", negativeKeywordConstraints));
		}
	
		System.out.println("");

		
		App.runSearch(args, input, isPartitionedBySize, additionalKeywordConstraints, negativeKeywordConstraints, minStars, updatedAfterYear, isNotApi);
	}

}
