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
 * Github Search Engine
 *
 */
public class App {

	// run multiple token

	// number of needed file to be resolved
	// will be overwritten afterwards. These are not constants lol
	public static int MAX_RESULT = 20;
	public static int MAX_TO_INSPECT = 50_000; // should increase this number?

	// folder location to save the downloaded files and jars
	// HJ notes : these are not actually constants....
	public static String DATA_LOCATION = "src/main/java/com/project/githubsearch/data/";
	private static String DATA_LOCATION_FAILED = "src/main/java/com/project/githubsearch/failed_data/";

	private static final String endpoint = "https://api.github.com/search/code";

	public static SynchronizedFeeder synchronizedFeeder;
	private static ResolvedFiles resolvedFiles = new ResolvedFiles();
	private static SynchronizedTypeSolver synchronizedTypeSolver = new SynchronizedTypeSolver();

	private static Instant start;

	private static Map<Integer, Integer> starsOnRepo = new HashMap<>();

	public final static boolean debug = false;
	public static boolean isRareApi = false;
	public static int totalExpectedResults = -1;
	public static String repo;

	
	//tech debt!
	// TODO fix bad API
	public static void reset() {
		DATA_LOCATION = "src/main/java/com/project/githubsearch/data/";
		DATA_LOCATION_FAILED = "src/main/java/com/project/githubsearch/failed_data/";
		SourceCodeAcceptor.reset();
		resolvedFiles = new ResolvedFiles();
		totalExpectedResults = -1;
		starsOnRepo.clear();
		isRareApi = false;	
		repo = null;
	}
	
	public static void main(String[] args) {
		System.out.println("args: " + Arrays.toString(args));
		String input = args[0];

		int numberToRetrieve = Integer.parseInt(args[1]);
		MAX_RESULT = numberToRetrieve; // not exactly. This is the number of unique candidate-usage that is wanted
		MAX_TO_INSPECT = MAX_RESULT * 20; // can't keep checking forever, we stop after looking at MAX_TO_INSPECT files

		System.out.println("Maximum files to inspect=" + MAX_TO_INSPECT);

		// token
		synchronizedFeeder = new SynchronizedFeeder(args[2].split(","));

		boolean isPartitionedBySize = true; // true if we want to split up the queries by size

		List<String> additionalKeywordConstraints = new ArrayList<>();
		List<String> negativeKeywordConstraints = new ArrayList<>();
		// additional constraints may be useful for queries that are really hard to
		// filter
		// e.g. new String(bytes, Charset).
		// "String" appears everywhere, but Charset doesn't
		// hence having the charset constraint is useful as input to github!
		int minStars = -1;
		int updatedAfterYear = 1970;
		boolean isNotApi = false;
		
		if (args.length > 3) {
			// args[5] and beyond
			for (int i = 3; i < args.length; i++) {
				if (!args[i].startsWith("--")) {
					String additionalKeywordsCommaSeparated = args[i];

					additionalKeywordConstraints = Arrays.asList(additionalKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--plus=")) {

					String additionalKeywordsCommaSeparated = args[i].split("--plus=")[1];
					additionalKeywordConstraints = Arrays.asList(additionalKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--not=")) {

					String negativelKeywordsCommaSeparated = args[i].split("--not=")[1];
					negativeKeywordConstraints = Arrays.asList(negativelKeywordsCommaSeparated.split(","));
				} else if (args[i].startsWith("--star=")) {
					try {
						minStars = Integer.parseInt(args[i].split("--star=")[1]);
					} catch (NumberFormatException e) {
						throw new RuntimeException("invalid --star value. You provided --star=" + args[i]
								+ ", which could not be parsed, causing a NumberFormatException");
					}
				} else if (args[i].startsWith("--updated_after=")) {
					updatedAfterYear = Integer.parseInt(args[i].split("--updated_after=")[1]);
				} else if (args[i].startsWith("--api=")) {
					isNotApi = !Boolean.parseBoolean(args[i].split("--api=")[1]);
				} else if (args[i].startsWith("--size=")) {
					isPartitionedBySize = Boolean.parseBoolean(args[i].split("--size=")[1]);
				} else if (args[i].startsWith("--cocci=")) {
					String cocciPath = args[i].split("--cocci=")[1]; // unused
				} else if (args[i].startsWith("--rare=")) {
					
					isRareApi = Boolean.parseBoolean(args[i].split("--rare=")[1]);
				} else if (args[i].startsWith("--repo=")) {
					
					repo = args[i].split("--repo=")[1];
				}
			}
		}

		System.out.println("You are searching for " + OutputUtils.ANSI_PURPLE + input + OutputUtils.ANSI_RESET);
		System.out.println("The search space will " + OutputUtils.ANSI_BLUE + (isPartitionedBySize ? "" : "NOT ")
				+ "be partitioned by size " + OutputUtils.ANSI_RESET
				+ "(required for scaling beyond the limits of the number of search result by GitHub)");
		if (isNotApi) {
			System.out.println(
					"Types will NOT" + OutputUtils.ANSI_BLUE + " be resolved (--api=false)" + OutputUtils.ANSI_RESET);
		}
		if (minStars > 0) {
			System.out.println("There is a minimum star threshold that the repo must exceed (" + minStars + ")");
		}

		if (additionalKeywordConstraints.size() > 0 || negativeKeywordConstraints.size() > 0) {
			System.out.println("Other keywords to pass to GitHub:");
			System.out.println(OutputUtils.ANSI_BLUE + "additional (will be biased towards results with these words):"
					+ OutputUtils.ANSI_RESET + String.join(",", additionalKeywordConstraints));
			System.out.println(OutputUtils.ANSI_BLUE + "negative (will completely filter results with these words):"
					+ OutputUtils.ANSI_RESET + String.join(",", negativeKeywordConstraints));
		}
		if (updatedAfterYear != 1970) {
			System.out.println(OutputUtils.ANSI_RED + "Updated after IS NOT IMPLEMENTED. IT WILl BE IGNORED"
					+ OutputUtils.ANSI_RESET);
		}

		System.out.println("");

		runSearch(input, isPartitionedBySize, additionalKeywordConstraints, negativeKeywordConstraints, minStars,
				updatedAfterYear, isNotApi, repo);
		System.out.println("args were: " + Arrays.toString(args));
	}

	public static List<String> runSearch(String input, boolean isPartitionedBySize,
			List<String> additionalKeywordConstraints, List<String> negativeKeywordConstraints, int minStars,
			int updatedAfterYear, boolean isNotApi, String repo) {
		Query query = parseQuery(input, additionalKeywordConstraints, isNotApi);

		printQuery(query);

		initUniqueFolderToSaveData(query, isPartitionedBySize, repo);
		if (start == null) {
			start = Instant.now();
		}

		initLabelFile();

		// use a initial request, without partitioning the results
		// just do a quick hack... 
		// TODO obviously, if we can already use this to get all our results, then we don't need to run more things again..
		Response initialReq = handleCustomGithubRequest(query.toStringRequest(), 1, 50, repo);
		System.out.println("initial results size = " + initialReq.getTotalCount());
		
		if (initialReq.getTotalCount() == 0) {
			// don't need to run anything...
			logTimingStatistics();
			System.out.println(OutputUtils.ANSI_YELLOW  + "There are no results at all. The search query might be malformed." + OutputUtils.ANSI_RESET);
			return new ArrayList<>();
		}
		
		if (!isRareApi) {// if the user hasn't set it as rare API 
			if (initialReq.getTotalCount() < 50) {
				System.out.println("initial results too few. Setting Rare API=true");
				isRareApi = true;
				System.out.println("totalExpectedResults = " + totalExpectedResults);
			}
		}
		totalExpectedResults = initialReq.getTotalCount(); // once reach this number of results, no need to proceed further.
		
		List<String> filePaths = processQuery(query, negativeKeywordConstraints, minStars,
				updatedAfterYear, isNotApi);

		// Done. Print metadata and statistics
		writeMetadata();

		logTimingStatistics();

		return filePaths;
	}

	private static void initLabelFile() {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(getLabelFilePath()))) {
			writer.write("id" + "," + "label");
			writer.write("\n");
		} catch (IOException e) {

			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private static List<String> getSnippetCode(String pathFile, List<Integer> lineNumbers) {
		List<String> codes = new ArrayList<String>();

		if (lineNumbers.isEmpty()) {
			throw new RuntimeException("Should not be empty!!");
		}

		int min = lineNumbers.stream().min(Integer::compare).get();
		int max = lineNumbers.stream().max(Integer::compare).get();

		try (BufferedReader reader = new BufferedReader(new FileReader(pathFile))) {
			int i = 0;

			String line = reader.readLine();
			while (line != null) {
				i++;

				if (i < (max + 5) && i > (min - 5)) {
					codes.add(line);
				}
				line = reader.readLine();
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		return codes;
	}

	/**
	 * 
	 * @param query
	 * @param isSplitBySize
	 * @param negativeKeywordConstraint
	 * @param minStars
	 * @param updatedAfterYear
	 * @param isNotApi
	 * @return list of paths to the downloaded files
	 */
	private static List<String> processQuery(Query query, List<String> negativeKeywordConstraint,
			int minStars, int updatedAfterYear, boolean isNotApi) {

		String queryStr = query.toStringRequest();

		int lowerBound = 0, upperBound = isRareApi ? 5000 : 250, page, perPageLimit;

		page = 1;
		perPageLimit = 30;

		int id = 0;
		Optional<String> nextUrlRequest = Optional.empty();

		List<String> output = new ArrayList<>();

		int numberOfConsecutivePartitionsWithoutItems = 0; // once this gets large, we can terminate early

		while (resolvedFiles.getResolvedFiles().size() < MAX_RESULT && id < MAX_TO_INSPECT && lowerBound < 200_000) {
			if (id % 50 == 0) {
				logTimingStatistics();
			}

			Response response;
			if (!nextUrlRequest.isPresent()) {
				// moving on to the next size partition!
				page = 1;

				String size = lowerBound + ".." + upperBound;
				response = handleCustomGithubRequest(queryStr, size, page, perPageLimit);

				lowerBound += isRareApi ? 5000 : 250;
				upperBound += isRareApi ? 5000 : 250;

				if (response.getTotalCount() == 0) {
					System.out.println("No item matches the query. Continuing to the next partition (size-based) lower_bound=" + lowerBound);
					logTimingStatistics();

					numberOfConsecutivePartitionsWithoutItems += 1;
					boolean shouldBreakEarly = false;
					if (numberOfConsecutivePartitionsWithoutItems >= 35 ) {
						System.out.println(
							"but we have failed too many times! There are no items for the 35th time! lower_bound=" + lowerBound);
						shouldBreakEarly = true;
					}
					System.out.println("comparing " + totalExpectedResults + " with id=" + id);
					if (totalExpectedResults >= 0 && id >= totalExpectedResults) {
						System.out.println("seen total expected items = " + totalExpectedResults);
						shouldBreakEarly = true;
					}
					if (shouldBreakEarly) {
						System.out.println("Breaking");
						break;
					}
					
					continue;
				}
			} else {
				response = handleGithubRequestWithUrl(nextUrlRequest.get());
			}

			numberOfConsecutivePartitionsWithoutItems = 0;
			JSONArray item = response.getItem();
			nextUrlRequest = response.getNextUrlRequest();

			Queue<String> data = new LinkedList<>();

			Map<String, Integer> repoStarsForBatch = new HashMap<>();
			Map<String, Integer> repoYearForBatch = new HashMap<>();
			for (int it = 0; it < item.length(); it++) {
				JSONObject instance = new JSONObject(item.get(it).toString());

				data.add(instance.getString("html_url"));

				JSONObject repo = instance.getJSONObject("repository");

				String repoUrl = repo.getString("url");
				repoStarsForBatch.put(instance.getString("html_url"), fetchStarGazers(repoUrl));

				repoYearForBatch.put(instance.getString("html_url"), fetchYearUpdated(repoUrl));
			}

			while (!data.isEmpty()) {
				String htmlUrl = data.remove();
				id++;
				
				

				System.out.println();
				System.out.println("\tID: " + id);
				if (debug) {
					System.out.println("\tFile Url: " + htmlUrl);
				}

				try {
					String downloadedPath = downloadAndResolveFile(id, htmlUrl, query, repoStarsForBatch.get(htmlUrl),
							negativeKeywordConstraint, minStars, isNotApi);
					if (downloadedPath != null)
						output.add(downloadedPath);
				} catch (IOException e) {
					e.printStackTrace();
					throw new RuntimeException(e);

				}
				int everyXtimes = debug ? 10 : 50;
				if (id % everyXtimes == 0) {
					System.out.println(OutputUtils.ANSI_YELLOW
							+ "# Types of instances, unique at the file-level, seen (note- not necessarily actual API usages): "
							+ SourceCodeAcceptor.resolvable + OutputUtils.ANSI_RESET);
					if (debug) {
						System.out.println("\tSize lower-bound=" + lowerBound);
					}
				}

				if (resolvedFiles.getResolvedFiles().size() >= MAX_RESULT && id >= MAX_TO_INSPECT) {
					System.out.println(OutputUtils.ANSI_RED + "Terminating search as inspected too many files"
							+ OutputUtils.ANSI_RESET);
					System.out.println("\t current id=" + id + ". MAX TO INSPECT=" + MAX_TO_INSPECT);
					System.out.println("\t # resolved file =" + resolvedFiles.getResolvedFiles().size()
							+ ". MAX_RESULT=" + MAX_RESULT);
					break;
				}
			}
		}

		return output;
	}

	private static void writeMetadata() {
		logTimingStatistics();
		System.out.println();
		System.out.println("\t\t===== Statistics about instances that we managed to resolve =====");
		System.out.println("\t\t\t\t<id>: <Number of similar copies found>");
		int total = 0;
		for (Entry<Integer, Boolean> entry : SourceCodeAcceptor.canonicalCopiesResolvable.entrySet()) {
			if (!entry.getValue())
				continue;

			System.out.println("\t\t\t\t===\t" + entry.getKey() + "\t:\t"
					+ SourceCodeAcceptor.canonicalCopiesCount.get(entry.getKey()) + "\t===");
			total += SourceCodeAcceptor.canonicalCopiesCount.get(entry.getKey());
		}

		System.out.println();
		System.out.println("Total files (including clones): " + total);
		System.out.println("Total unique files: " + SourceCodeAcceptor.resolvable);

		String metadataDirectory = DATA_LOCATION + "metadata/";
		if (!new File(metadataDirectory).exists()) {
			new File(metadataDirectory).mkdirs();
		}

		System.out.println(OutputUtils.ANSI_YELLOW + "Files are written to " + DATA_LOCATION + OutputUtils.ANSI_RESET);
		System.out.println("Writing metadata to " + metadataDirectory + "metadata.csv");
		System.out.println("\t\t and " + metadataDirectory + "metadata_locations.csv");
		System.out.println("\t\t and " + metadataDirectory + "metadata_stars.csv");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_LOCATION + "metadata/metadata.csv"))) {
			for (Entry<Integer, Boolean> entry : SourceCodeAcceptor.canonicalCopiesResolvable.entrySet()) {
				if (!entry.getValue())
					continue;

				writer.write(entry.getKey() + "," + SourceCodeAcceptor.canonicalCopiesCount.get(entry.getKey()) + "\n");

			}
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Unable to write metadata ...");
		}

		try (BufferedWriter writer = new BufferedWriter(
				new FileWriter(DATA_LOCATION + "metadata/metadata_locations.csv"))) {
			for (Entry<Integer, Boolean> entry : SourceCodeAcceptor.canonicalCopiesResolvable.entrySet()) {
				if (!entry.getValue())
					continue;

				int i = 0;
				for (String url : SourceCodeAcceptor.canonicalCopiesUrl.get(entry.getKey())) {
					writer.write(entry.getKey() + "," + i + "," + url + "\n");
					i++;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Unable to write metadata ...");
		}

		Set<Integer> alreadyWritten = new HashSet<>();
		try (BufferedWriter writer = new BufferedWriter(
				new FileWriter(DATA_LOCATION + "metadata/metadata_stars.csv"))) {

			for (Entry<Integer, Integer> starEntry : starsOnRepo.entrySet()) {
				if (alreadyWritten.contains(starEntry.getKey()))
					continue;
				writer.write(starEntry.getKey() + "," + starEntry.getValue() + "\n");
				alreadyWritten.add(starEntry.getKey());
			}

		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Unable to write metadata ...");
		}
	}

	private static List<String> readLineByLine(String filePath) {
		List<String> contentBuilder = new ArrayList<>();

		try (Stream<String> stream = Files.lines(Paths.get(filePath), StandardCharsets.UTF_8)) {
			stream.forEach(s -> contentBuilder.add(s));
		} catch (IOException e) {
			System.err.println("Unable to read due to IO exception" + e);
			e.printStackTrace();
		} catch (Exception e) {
			System.err.println("Unable to read due to exception" + e);
			e.printStackTrace();
		}

		return contentBuilder;
	}

	public static String downloadAndResolveFile(int id, String htmlUrl, Query query, int stars,
			List<String> negativeKeywordConstraint, int minStars, boolean isNotApi) throws IOException {

		Optional<RejectReason> rejectReason;
		List<String> lines = new ArrayList<>();
		String filePath = null;
		
		if (stars < minStars) {
			rejectReason = Optional.of(RejectReason.NOT_ENOUGH_STARS);

		} else {
			Optional<String> filePathOpt = downloadFile(htmlUrl, id);
			if (!filePathOpt.isPresent())
				return null;

			filePath = filePathOpt.get();

			lines = readLineByLine(filePath); // if fail due to some exception, then it will be empty
			
			rejectReason = SourceCodeAcceptor.accept(id, htmlUrl, lines, stars, starsOnRepo,
					negativeKeywordConstraint, minStars);
		}
		
		System.out.print(OutputUtils.ANSI_WHITE + " \t.. from " + htmlUrl + OutputUtils.ANSI_RESET);
		if (!lines.isEmpty() && !rejectReason.isPresent()) {

			Optional<ResolvedFile> resolvedFileOpt;
			if (isNotApi) {
				// if not api
				// resolvedFile isn't exactly "resolved"
				// but we pretend that it is, to maintain backwards compat with the previous
				// code
				resolvedFileOpt = getResolvedFileOfNonApi(filePath, query);
			} else {
				resolvedFileOpt = resolveFile(filePath, query);
			}

			if (resolvedFileOpt.isPresent()) {
				System.out.println(OutputUtils.ANSI_GREEN + "\t\tAccepted!" + OutputUtils.ANSI_RESET);
				ResolvedFile resolvedFile = resolvedFileOpt.get();

				resolvedFile.setUrl(htmlUrl);

				if (debug) {
					logTimingStatistics();
					System.out.println("\t\tURL: " + resolvedFile.getUrl());
					System.out.println("\t\tPath to File: " + resolvedFile.getPathFile());
					System.out.println("\t\tLine: " + resolvedFile.getLines());
					System.out.println("\t\tSnippet Code: ");
				}

				List<String> codes = getSnippetCode(resolvedFile.getPathFile(), resolvedFile.getLines());
				if (debug) {
					for (int j = 0; j < Math.min(codes.size(), 10); j++) {
						System.out.println(codes.get(j));
					}
				}

				resolvedFiles.add(resolvedFile);

				SourceCodeAcceptor.indicateCanBeResolved(id);

				// move file to directory such that the package name is respected.
				// (usually useful for further analysis)
				String[] splitted = htmlUrl.split("/");
				String className = splitted[splitted.length - 1];
				String packageName = resolvedFile.getPackageName();

				Files.copy(new File(filePath).toPath(),
						new File(DATA_LOCATION + "cocci_files" + "/" + id + "." + className + ".txt").toPath());

				if (!packageName.isEmpty()) {
					String packageDirectories = packageName.replaceAll("\\.", "/");

					File expectedFileLocation = new File(DATA_LOCATION + id + "/" + packageDirectories + "/");
					if (!expectedFileLocation.exists()) {
						expectedFileLocation.mkdirs();
					}
					Files.copy(new File(filePath).toPath(),
							new File(expectedFileLocation.toString() + "/" + className + ".txt").toPath());
					new File(filePath)
							.renameTo(new File(DATA_LOCATION + "files" + "/" + id + "." + className + ".txt"));
					System.out.println(OutputUtils.ANSI_YELLOW + "\tmoved file to "
							+ new File(expectedFileLocation.toString() + "/" + className + ".txt")
							+ OutputUtils.ANSI_RESET);

					return DATA_LOCATION + "files" + "/" + id + "." + className + ".txt";
				} else {
					// no package
					Files.copy(new File(filePath).toPath(),
							new File(DATA_LOCATION + "files" + "/" + id + "." + className + ".txt").toPath());
					System.out.println(OutputUtils.ANSI_YELLOW + "\tcopied file to "
							+ new File(DATA_LOCATION + "files" + "/" + id + "." + className + ".txt").toPath()
							+ OutputUtils.ANSI_RESET);

					return DATA_LOCATION + "files" + "/" + id + "." + className + ".txt";
				}

			} else {

			}
		} else {
			// early return if failed
			System.out.println();
			System.out.println("\t\t" + OutputUtils.ANSI_RED + "Rejected: " + OutputUtils.ANSI_RESET + // " url=" +
																										// htmlUrl +
					"It " + rejectReason.get().description());

		}

		// move file from DATA_LOCATION to DATA_LOCATION_FAILED
		boolean cleanUpNeeded = filePath != null && new File(filePath).exists();
		if (debug) {
			if (cleanUpNeeded) {
				System.out.println("\tmoving file to " + DATA_LOCATION_FAILED);
				new File(filePath).renameTo(new File(DATA_LOCATION_FAILED + "files/" + id + ".txt"));
			}
		} else {
			if (cleanUpNeeded) {
				new File(filePath).delete(); // save space.
				System.out.println(OutputUtils.ANSI_YELLOW + "\tdeleting file" + OutputUtils.ANSI_RESET);
			}

		}
		if (cleanUpNeeded) {
			String oldFileDirectory = filePath.substring(0, filePath.lastIndexOf('/'));
			new File(oldFileDirectory).delete();
		}

		return null;
	}

	private static String getLabelFilePath() {
		return DATA_LOCATION + "labels.csv";
	}

	
	private static String lastLog;
	private static void logTimingStatistics() {
		Instant currentTime = Instant.now();
		long timeElapsed = Duration.between(start, currentTime).toMillis();
		long minutes = (timeElapsed / 1000) / 60;
		long seconds = (timeElapsed / 1000) % 60;
		long ms = (timeElapsed % 1000);
		String log = "\tTotal elapsed time: " + minutes + " minutes " + seconds + " seconds " + ms + "ms";
		
		if (lastLog != null && lastLog.equals(log)) {
			return;
		}
		System.out.println(log);
		
		lastLog = log;
	}

	private static Optional<String> downloadFile(String htmlUrl, int fileId) {
		// convert html url to downloadable url
		String downloadableUrl = convertHTMLUrlToDownloadUrl(htmlUrl);

		String[] splitted = htmlUrl.split("/");
		String className = splitted[splitted.length - 1];

		// using it to make a unique name
		// replace java to txt for excluding from maven builder
		String fileName = className + ".txt";

		try {
			// download file from url
			URL url;
			url = new URL(downloadableUrl);
			ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
			String pathFile = DATA_LOCATION + fileId + "/" + fileName;

			if (!new File(DATA_LOCATION + fileId).exists()) {
				if (!new File(DATA_LOCATION + fileId).mkdirs()) {
					throw new RuntimeException("Cannot make directories");
				}
			}

			System.out.println(OutputUtils.ANSI_YELLOW + "\tdownloaded to " + pathFile + " for checking");

			FileOutputStream fileOutputStream = new FileOutputStream(pathFile);
			fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
			fileOutputStream.close();

//			System.out.println(" succeeded!");

			return Optional.of(pathFile);

		} catch (FileNotFoundException e) {
			System.out.println("Can't download the file from github");
			System.out.println("File not found!");
			e.printStackTrace();
		} catch (MalformedURLException e) {
			System.out.println("Malformed URL Exception while downloading!");
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Can't save the downloaded file");
		}

		return Optional.empty();
	}

	private static Optional<ResolvedFile> resolveFile(String filePath, Query query) {

		return resolve(query, filePath);

	}

	private static Optional<ResolvedFile> getResolvedFileOfNonApi(String filePath, Query query) throws IOException {

		List<String> snippetCodes = new ArrayList<String>();

		ResolvedFile resolvedFile = new ResolvedFile(query, "", "", new ArrayList<>(), snippetCodes);

		// as long as the text appears, we consider that it "matches"
		List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);

		String[] parts = query.getFullyQualifiedClassName().split("\\.");
		Set<String> matchParts = new HashSet<>();

		List<Integer> lineNumbers = new ArrayList<>();

		// e.g. if all of "android", "annotation", "SuppressLint" appears in the file,
		// we can guess that it has indeed the right annotation
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			for (String part : parts) {
				if (line.contains(part)) {
					matchParts.add(part);

					lineNumbers.add(i);

					resolvedFile.setPathFile(filePath);
					resolvedFile.setLines(lineNumbers);
					resolvedFile.setCodes(getSnippetCode(filePath, lineNumbers));
				}
			}
		}

		if (matchParts.size() != parts.length) {
			// it failed
			return Optional.empty();
		} else {
			return Optional.of(resolvedFile);
		}

	}

	private static Optional<ResolvedFile> resolve(Query query, String pathFile) {
		File file = new File(pathFile);

		List<String> snippetCodes = new ArrayList<String>();
		List<Integer> lines = new ArrayList<Integer>();

		ResolvedFile resolvedFile = new ResolvedFile(query, "", "", lines, snippetCodes);
		try {
			List<String> addedJars = JarRetriever.getNeededJars(file);
			for (int i = 0; i < addedJars.size(); i++) {
				try {
					TypeSolver jarTypeSolver = JarTypeSolver.getJarTypeSolver(addedJars.get(i));
					synchronizedTypeSolver.add(jarTypeSolver);
				} catch (Exception e) {
					System.out.println("\t! Package corrupt! !");
					System.out.println("\t\tCorrupted jars: " + addedJars.get(i));
					System.out.println("\t\tPlease download the latest jar manually from maven repository!");
					System.out.println("\t\tFile location: " + file.toString());
				}
			}
			StaticJavaParser.getConfiguration()
					.setSymbolResolver(new JavaSymbolSolver(synchronizedTypeSolver.getTypeSolver()));
			CompilationUnit cu = StaticJavaParser.parse(file);

			Optional<PackageDeclaration> packageName = cu.getPackageDeclaration();
			if (packageName.isPresent()) {
				resolvedFile.setPackageName(packageName.map(packageDecl -> packageDecl.getNameAsString()).get());

			}

			if (query.isQueryForConstructor()) {
				List<ObjectCreationExpr> objectCreationExprs = cu.findAll(ObjectCreationExpr.class);
				return matchObjectCreationCalls(query, pathFile, lines, resolvedFile, objectCreationExprs);
			} else {
				List<MethodCallExpr> methodCallExprs = cu.findAll(MethodCallExpr.class);
				return matchMethodCalls(query, pathFile, lines, resolvedFile, methodCallExprs);
			}

		} catch (ParseProblemException parseProblemException) {
			System.out.println("===== Unable to parse (This is usually fine, we will ignore this file.) ===");
			System.out.println("Exception is " + parseProblemException);
			System.out.println("File location: " + pathFile);
		} catch (IOException io) {
			System.out.println("=== IO Exception during type resolution (This is usually fine, we will ignore this file.) ===");
			System.out.println("Exception is " + io);
			io.printStackTrace();
			System.out.println("File location: " + pathFile);
		} catch (RuntimeException runtimeException) {
			System.out.println("=== Runtime Exception during type resolution. (This is usually fine, we will ignore this file.) ===");
			System.out.println("Exception is " + runtimeException);
			runtimeException.printStackTrace();
			System.out.println("File location: " + pathFile);
		} catch (java.lang.StackOverflowError stackOverflow) {
			System.out.println("=== StackOverflowError during type resolution (This is usually fine, we will ignore this file.) ===");
			System.out.println("Error is " + stackOverflow);
			stackOverflow.printStackTrace();
			System.out.println("File location: " + pathFile);
		}

		return Optional.empty();
	}

	private static Optional<ResolvedFile> matchObjectCreationCalls(Query query, String pathFile, List<Integer> lines,
			ResolvedFile resolvedFile, List<ObjectCreationExpr> ctorlExprs) {

		boolean isMethodMatch = false;
		boolean isResolved = false;

		// HJ: for debugging
		List<String> methodCallNames = new ArrayList<>();

		for (int j = 0; j < ctorlExprs.size(); j++) {
			ObjectCreationExpr mce = ctorlExprs.get(j);

			methodCallNames.add(mce.getTypeAsString() + ":" + mce.getArguments().size());

			if (!query.getFullyQualifiedClassName().contains(mce.getTypeAsString().split("<")[0]) // don't want any
																									// generics "<T>"
																									// disrupting us
					|| mce.getArguments().size() != query.getArguments().size()) {
				// ignore if different name or different number of arguments
				continue;
			}

			isMethodMatch = true;
			try {
				ResolvedConstructorDeclaration resolvedMethodDeclaration = mce.resolve();

				String fullyQualifiedCtor = resolvedMethodDeclaration.getPackageName() + "."
						+ mce.getTypeAsString().split("<")[0];

				if (fullyQualifiedCtor.equals(query.getFullyQualifiedClassName())) {

					lines.add(mce.getBegin().get().line);

					isResolved = true;
				} else {
					if (debug) {
						System.out.println("\t\tfailed to match " + fullyQualifiedCtor + " against "
								+ query.getFullyQualifiedClassName());
					}
				}

			} catch (UnsolvedSymbolException use) {
				System.out.println("\t\tunsolvedSymbolException in resolveFile");
				System.out.println("\t\tsymbol is " + use.getName());
			}

		}

		if (!isMethodMatch) {
			System.out.println("\t\tNo method match : " + query.getFullyQualifiedClassName());
			System.out.println("\t\t = " + query);
//			System.out.println("\t\tnames in file: " + methodCallNames);
			if (debug) {
				System.out.println("\t\tnames in file: " + methodCallNames);
			}
		}
		if (!isResolved) {
			if (debug) {
				System.out.println("\t\tCan't resolve : " + query.getFullyQualifiedClassName());
			}
		}

		if (isMethodMatch && isResolved) {
			resolvedFile.setPathFile(pathFile);
			resolvedFile.setLines(lines);
			resolvedFile.setCodes(getSnippetCode(pathFile, lines));
			System.out.println("\t=== SUCCESSFULLY RETRIEVED EXAMPLE ===");
			return Optional.of(resolvedFile);
		} else {
			return Optional.empty();
		}
	}

	private static Optional<ResolvedFile> matchMethodCalls(Query query, String pathFile, List<Integer> lines,
			ResolvedFile resolvedFile, List<MethodCallExpr> methodCallExprs) {

		boolean isMethodMatch = false;
		boolean isResolved = false;
		boolean isFullyQualifiedClassNameMatch = false;

		// HJ: for debugging
		List<String> methodCallNames = new ArrayList<>();
		List<String> closeMethodCallNames = new ArrayList<>(); // names that only differ because the FQN check
																// failed

		for (int j = 0; j < methodCallExprs.size(); j++) {
			MethodCallExpr mce = methodCallExprs.get(j);

			methodCallNames.add(mce.getName().toString() + ":" + mce.getArguments().size());

			if (!mce.getName().toString().equals(query.getMethod())
					|| mce.getArguments().size() != query.getArguments().size()) {
				// ignore if different name or different number of arguments
				continue;
			}

			isMethodMatch = true;
			List<String> fullyQualifiedInterfaceNames = new ArrayList<>();
			String fullyQualifiedClassName = "";
			try {
				ResolvedMethodDeclaration resolvedMethodDeclaration = mce.resolve();

				fullyQualifiedClassName = resolvedMethodDeclaration.getPackageName() + "."
						+ resolvedMethodDeclaration.getClassName();

				// make some wild guesses

				if (resolvedMethodDeclaration.declaringType().isClass()
						|| resolvedMethodDeclaration.declaringType().isAnonymousClass()) {
					List<ResolvedReferenceType> interfaces = resolvedMethodDeclaration.declaringType().asClass()
							.getAllInterfaces();
					for (ResolvedReferenceType singleInterface : interfaces) {
						String fullyQualifiedInterfaceMethodName = singleInterface.getQualifiedName(); // + "#"+
																										// mce.getNameAsString();
						fullyQualifiedInterfaceNames.add(fullyQualifiedInterfaceMethodName);
					}
				} else if (resolvedMethodDeclaration.declaringType().isInterface()) {
					List<ResolvedReferenceType> interfaces = resolvedMethodDeclaration.declaringType().asInterface()
							.getAllInterfacesExtended();
					for (ResolvedReferenceType singleInterface : interfaces) {
						String fullyQualifiedInterfaceMethodName = singleInterface.getQualifiedName(); // + "#" +
																										// mce.getNameAsString();
						fullyQualifiedInterfaceNames.add(fullyQualifiedInterfaceMethodName);
					}

				}

				isResolved = true;

			} catch (UnsolvedSymbolException use) {
				System.out.println("\t\tunsolvedSymbolException in resolveFile");
				System.out.println("\t\tsymbol is " + use.getName());
			} catch (java.lang.IllegalAccessError iae) {
				System.out.println("\t!!! A shocking IllegalAccessError!");
				iae.printStackTrace();
				System.out.println("\t!!! Ignore it!");
			}

			if (fullyQualifiedClassName.equals(query.getFullyQualifiedClassName())
					|| fullyQualifiedInterfaceNames.contains(query.getFullyQualifiedClassName())) {

				isFullyQualifiedClassNameMatch = true;
				lines.add(mce.getBegin().get().line);

			} else {
				if (!fullyQualifiedClassName.isEmpty()) {
					closeMethodCallNames.add(fullyQualifiedClassName + "#" + mce.getNameAsString());
				}
				for (String fullyQualifiedInterfaceName : fullyQualifiedInterfaceNames) {
					closeMethodCallNames.add(fullyQualifiedInterfaceName + "#" + mce.getNameAsString());
				}
			}

		}

		if (!isMethodMatch) {
			System.out.println("\t\tNo method match : " + query.getMethod());
//			System.out.println("\t\t\tnames in file: " + methodCallNames);
			if (debug) {
				System.out.println("\t\t\tnames in file: " + methodCallNames);
			}
		}
		if (isMethodMatch && !isResolved) {
			System.out.println("\t\tCan't resolve :" + query.getMethod());
		}
		if (isResolved && !isFullyQualifiedClassNameMatch) {
			System.out.println("\t\tFailed FQN check: Fully qualified names are " + closeMethodCallNames);
			System.out.println("\t\t\tExpected name is " + query.getFullyQualifiedClassName());
		}

		if (isMethodMatch && isResolved && isFullyQualifiedClassNameMatch) {
			resolvedFile.setPathFile(pathFile);
			resolvedFile.setLines(lines);
			resolvedFile.setCodes(getSnippetCode(pathFile, lines));
			System.out.println("\t=== SUCCESSFULLY RETRIEVED EXAMPLE ===");
			return Optional.of(resolvedFile);
		} else {
			return Optional.empty();
		}
	}

	private static void printQuery(Query query) {
		System.out.println("============");
		System.out.println("Your Queries");
		System.out.println("============");

		System.out.println("Query " + ": " + query);
		System.out.println("Additional keywords " + ": " + query.getAdditionalKeywords());

	}

	public static Query parseQuery(String s, List<String> additionalKeywordConstraints, boolean isNotApi) {
		s = s.replace(" ", "");

		String fullyQualifiedClassName;
		String method;
		String args;
		if (!isNotApi) {
			int hashLocation = s.indexOf('#');
			int leftBracketLocation = s.indexOf('(');
			int rightBracketLocation = s.indexOf(')');
			if (hashLocation == -1) {
				System.out.println("Your query isn't accepted");
				System.out.println("Query Format: " + "method");
				System.out.println("Example: " + "android.app.Notification.Builder#addAction(argument, ...)");

				throw new RuntimeException("wrong query format!");
			}
			fullyQualifiedClassName = s.substring(0, hashLocation);
			method = s.substring(hashLocation + 1, leftBracketLocation);
			args = s.substring(leftBracketLocation + 1, rightBracketLocation);
		} else {
			// not api
			// just pretend its a class
			method = s;
			args = "";
			fullyQualifiedClassName = s;
		}

		List<String> arguments = new ArrayList<>();
		if (!args.isEmpty()) {
			String[] arr = args.split(",");
			for (int i = 0; i < arr.length; i++) {
				arguments.add("DUMMY"); // ignore the parameter types
			}
		}

		Query query = new Query();
		query.setFullyQualifiedClassName(fullyQualifiedClassName);
		query.setMethod(method);
		query.setArguments(arguments);
		query.setAdditionalKeywords(additionalKeywordConstraints);
		return query;
	}

	private static void initUniqueFolderToSaveData(Query query, boolean isSplitBySize, String repo) {

		String folderName = nameOfFolder(query, isSplitBySize, repo);

		makeFileResolutionLocation(folderName);

		makeFailedFilesLocation(folderName);

		new File(DATA_LOCATION + "cocci_files").mkdir();

		File jarFolder = new File(JarRetriever.JARS_LOCATION);
		if (!jarFolder.exists()) {
			jarFolder.mkdir();
		}

	}

	public static String nameOfFolder(Query query, boolean isSplitBySize, String repo) {
		String folderName = query.getFullyQualifiedClassName() + "__" + query.getMethod() + "__"
				+ query.getArguments().size();

		if (repo != null) {
			folderName += "__" + repo.replace("/", "_");
		}
		
		if (!query.getAdditionalKeywords().isEmpty()) {
			folderName += query.getAdditionalKeywords();
		}
		folderName += "_" + isSplitBySize;
		return folderName;
	}

	private static void makeFailedFilesLocation(String folderName) {
		File dataFolder = new File(DATA_LOCATION_FAILED);
		if (!dataFolder.exists()) {
			dataFolder.mkdir();
		}

		DATA_LOCATION_FAILED = DATA_LOCATION_FAILED + folderName + "/";
		File exactFolder = new File(DATA_LOCATION_FAILED);
		if (!exactFolder.exists()) {
			exactFolder.mkdir();
		}

		File files = new File(DATA_LOCATION_FAILED + "files/");
		if (!files.exists()) {
			files.mkdir();
		}
	}

	private static void makeFileResolutionLocation(String folderName) {
		File dataFolder = new File(DATA_LOCATION);
		if (!dataFolder.exists()) {
			dataFolder.mkdir();
		}

		DATA_LOCATION = DATA_LOCATION + folderName + "/";

		File exactFolder = new File(DATA_LOCATION);
		if (!exactFolder.exists()) {
			exactFolder.mkdir();
		}

		File files = new File(DATA_LOCATION + "files/");
		if (files.exists()) {
			System.out.println("One should delete the previously collected files before rerunning this");
			System.out.println(OutputUtils.ANSI_RED + "Try deleting " + DATA_LOCATION + OutputUtils.ANSI_RESET);
			throw new RuntimeException(DATA_LOCATION + "files/"
					+ " seems to already exist. One should delete them before rerunning this.");
		}
		files.mkdirs();

	}

	/**
	 * Convert github html url to download url input:
	 * https://github.com/shuchen007/ThinkInJavaMaven/blob/85b402a81fc0b3f2f039b34c23be416322ef14b4/src/main/java/sikaiClient/IScyxKtService.java
	 * output:
	 * https://raw.githubusercontent.com/shuchen007/ThinkInJavaMaven/85b402a81fc0b3f2f039b34c23be416322ef14b4/src/main/java/sikaiClient/IScyxKtService.java
	 */
	private static String convertHTMLUrlToDownloadUrl(String html_url) {
		String[] parts = html_url.split("/");
		String download_url = "https://raw.githubusercontent.com/";
		int l = parts.length;
		for (int i = 0; i < l; i++) {
			if (i >= 3) {
				if (i != 5) {
					if (i != l - 1) {
						download_url = download_url.concat(parts[i] + '/');
					} else {
						download_url = download_url.concat(parts[i]);
					}
				}
			}
		}

		return download_url;
	}

	private static Response handleGithubRequestWithUrl(String url) {

		boolean response_ok = false;
		Response response = new Response();
		int responseCode;

		// encode the space into %20
		url = url.replace(" ", "%20");
		GithubToken token = synchronizedFeeder.getAvailableGithubToken();

		do {
			HttpRequest request = HttpRequest.get(url, false).authorization("token " + token.getToken());
			System.out.println();
			System.out.println("Request: " + request);

			responseCode = request.code();
			if (responseCode == Utils.RESPONSE_OK) {
				response.setCode(responseCode);
				JSONObject body = new JSONObject(request.body());
				response.setTotalCount(body.getInt("total_count"));
				if (body.getInt("total_count") > 0) {
					response.setItem(body.getJSONArray("items"));
					response.setUrlRequest(request.toString());
					response.setNextUrlRequest(getNextLinkFromResponse(request.header("Link")));
				} else {
					System.out.println("no 'next' header!");
				}
				response_ok = true;
			} else if (responseCode == Utils.BAD_CREDENTIAL) {
				System.out.println("Authorization problem");
				System.out.println("Please read the readme file!");
				System.out.println("Github message: " + (new JSONObject(request.body())).getString("message"));
				System.exit(-1);
			} else if (responseCode == Utils.ABUSE_RATE_LIMITS) {
				System.out.println("Received response code indicating Abuse Rate Limits");
				// retry current progress after wait for a minute
				String retryAfter = request.header("Retry-After");
				try {
					int sleepTime = 0; // wait for a while
					if (retryAfter == null || retryAfter.isEmpty()) {
						sleepTime = 70;
					} else {
						sleepTime = new Integer(retryAfter).intValue();
					}
					System.out.println("Retry-After: " + sleepTime + " seconds");
					TimeUnit.SECONDS.sleep(sleepTime);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} else if (responseCode == Utils.UNPROCESSABLE_ENTITY) {
				System.out.println("Response Code: " + responseCode);
				System.out.println("Unprocessable Entity: only the first 1000 search results are available");
				System.out.println("See the documentation here: https://developer.github.com/v3/search/");
			} else {
				System.out.println("Response Code: " + responseCode);
				System.out.println("Response Body: " + request.body());
				System.out.println("Response Headers: " + request.headers());
				try {
					int sleepTime = 60; // wait for a while

					System.out.println("Not sure what happened. So retry after: " + sleepTime + " seconds");
					TimeUnit.SECONDS.sleep(sleepTime);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

		} while (!response_ok && responseCode != Utils.UNPROCESSABLE_ENTITY);

		synchronizedFeeder.releaseToken(token);

		return response;
	}

	static Map<String, Integer> knownStars = new HashMap<>();
	static Map<String, Integer> knownYearUpdated = new HashMap<>();

	private static int fetchStarGazers(String url) {
		if (knownStars.containsKey(url)) {
			return knownStars.get(url);
		}

		// encode the space into %20
		url = url.replace(" ", "%20");
		GithubToken token = synchronizedFeeder.getAvailableGithubToken();

		JSONObject body = fetchGithubRepo(url, token);
		updateLocalStoreOfRepo(url, body);
		return knownStars.get(url);
	}

	private static int fetchYearUpdated(String url) {
		if (knownYearUpdated.containsKey(url)) {
			return knownYearUpdated.get(url);
		}

		// encode the space into %20
		url = url.replace(" ", "%20");
		GithubToken token = synchronizedFeeder.getAvailableGithubToken();

		JSONObject body = fetchGithubRepo(url, token);
		updateLocalStoreOfRepo(url, body);
		return knownYearUpdated.get(url);
	}

	private static void updateLocalStoreOfRepo(String url, JSONObject body) {
		int stars = body.getInt("stargazers_count");
		knownStars.put(url, stars);

		int lastUpdated = ZonedDateTime.parse(body.getString("updated_at")).getYear();
		knownYearUpdated.put(url, lastUpdated);
	}

	private static JSONObject fetchGithubRepo(String url, GithubToken token) {
		int responseCode;
		boolean response_ok = false;

		JSONObject body = null;
		do {
			HttpRequest request = HttpRequest.get(url, false).authorization("token " + token.getToken());

			responseCode = request.code();
			if (responseCode == Utils.RESPONSE_OK) {
				body = new JSONObject(request.body());
				response_ok = true;
//				System.out.println(body);

			} else if (responseCode == Utils.BAD_CREDENTIAL) {
				System.out.println("Authorization problem");
				System.out.println("Please read the readme file!");
				System.out.println("Github message: " + (new JSONObject(request.body())).getString("message"));
				System.exit(-1);
			} else if (responseCode == Utils.ABUSE_RATE_LIMITS) {
				System.out.println("Received response code indicating Abuse Rate Limits");
				// retry current progress after wait for a minute

				try {
					String retryAfter = request.header("Retry-After");
					int sleepTime; // wait for a while
					if (retryAfter == null || retryAfter.isEmpty()) {
						sleepTime = 70;
					} else {
						sleepTime = new Integer(retryAfter).intValue();
					}
					System.out.println("Retry-After: " + sleepTime + " seconds");
					TimeUnit.SECONDS.sleep(sleepTime);
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
					System.out.println(request);
					System.out.println("Response headers are:");
					System.out.println(request.headers());
					throw new RuntimeException(e);
				}
			} else if (responseCode == Utils.UNPROCESSABLE_ENTITY) {
				System.out.println("Response Code: " + responseCode);
				System.out.println("Unprocessable Entity: only the first 1000 search results are available");
				System.out.println("See the documentation here: https://developer.github.com/v3/search/");
			} else {
				System.out.println("Response Code: " + responseCode);
				System.out.println("Response Body: " + request.body());
				System.out.println("Response Headers: " + request.headers());
				try {

					int sleepTime = new Integer(60).intValue();

					System.out.println("Retry-After: " + sleepTime + " seconds");
					TimeUnit.SECONDS.sleep(sleepTime);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

		} while (!response_ok && responseCode != Utils.UNPROCESSABLE_ENTITY);

		synchronizedFeeder.releaseToken(token);
		return body;
	}

	private static Response handleCustomGithubRequest(String query, String size, int page, int per_page_limit) {
		// The size range is exclusive

		Response response = new Response();

		String url = endpoint + "?" + Utils.PARAM_QUERY + "=" + query + "+size:" + size + "+in:file+language:java" + "&"
				+ Utils.PARAM_PAGE + "=" + page + "&" + Utils.PARAM_PER_PAGE + "=" + per_page_limit;// +"&" + PARAM_SORT
																									// + "=indexed";
		response = handleGithubRequestWithUrl(url);

		return response;
	}
	
	// without size
	private static Response handleCustomGithubRequest(String query, int page, int per_page_limit, String repo) {
		// The size range is exclusive

		Response response = new Response();

		String url = endpoint + "?" + Utils.PARAM_QUERY + "=" + query + "+in:file+language:java" +
				(repo != null ? "+repo:" + repo : "")
				+ "&"
				+ Utils.PARAM_PAGE + "=" + page + "&" + Utils.PARAM_PER_PAGE + "=" + per_page_limit;// +"&" + PARAM_SORT
																									// + "=indexed";

		response = handleGithubRequestWithUrl(url);

		return response;
	}

	private static Optional<String> getNextLinkFromResponse(String linkHeader) {

		String next = null;

		if (linkHeader != null) {
			String[] links = linkHeader.split(Utils.DELIM_LINKS);
			for (String link : links) {
				String[] segments = link.split(Utils.DELIM_LINK_PARAM);
				if (segments.length < 2)
					continue;

				String linkPart = segments[0].trim();
				if (!linkPart.startsWith("<") || !linkPart.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
					continue;
				linkPart = linkPart.substring(1, linkPart.length() - 1);

				for (int i = 1; i < segments.length; i++) {
					String[] rel = segments[i].trim().split("="); //$NON-NLS-1$
					if (rel.length < 2 || !Utils.META_REL.equals(rel[0]))
						continue;

					String relValue = rel[1];
					if (relValue.startsWith("\"") && relValue.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
						relValue = relValue.substring(1, relValue.length() - 1);

					if (Utils.META_NEXT.equals(relValue))
						next = linkPart;
				}
			}
		}
		if (next == null) {
			System.out.println("printing stuff");
			System.out.println("linkHeader is " + linkHeader);
			System.out.println("Next url is null!");
			return Optional.empty();
		}

		return Optional.of(next);
	}

}
