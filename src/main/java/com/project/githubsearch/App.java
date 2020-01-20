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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
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

	// parameter for the request
	private static final String PARAM_QUERY = "q"; //$NON-NLS-1$
	private static final String PARAM_PAGE = "page"; //$NON-NLS-1$
	private static final String PARAM_PER_PAGE = "per_page"; //$NON-NLS-1$
	private static final String PARAM_SORT = "sort";

	// links from the response header
	private static final String META_REL = "rel"; //$NON-NLS-1$
	private static final String META_NEXT = "next"; //$NON-NLS-1$
	private static final String DELIM_LINKS = ","; //$NON-NLS-1$
	private static final String DELIM_LINK_PARAM = ";"; //$NON-NLS-1$

	// response code from github
	private static final int BAD_CREDENTIAL = 401;
	private static final int RESPONSE_OK = 200;
	private static final int ABUSE_RATE_LIMITS = 403;
	private static final int UNPROCESSABLE_ENTITY = 422;

	// number of needed file to be resolved
	private static int MAX_RESULT = 20; // 30 for local testing; set to 100 for server testing; then 500 for the
										// final run
	private static int MAX_TO_INSPECT = 10000; // should increase this number eventually?

	// folder location to save the downloaded files and jars
	// HJnotes : these are not actually constants....
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
		MAX_TO_INSPECT = MAX_RESULT * 200;
		
		System.out.println("Maximum files to inspect=" + MAX_TO_INSPECT);

		synchronizedFeeder = new SynchronizedFeeder(args[2].split(","));
		
		boolean isPartitionedBySize = Boolean.parseBoolean(args[3]); // true if we want to split up the queries by size
		System.out.println("partitioning by size =" + isPartitionedBySize);
		
		
		List<String> additionalKeywordConstraint = new ArrayList<>();
		// additional constraints may be useful for queries that are really hard to
		// filter
		// e.g. new String(bytes, Charset).
		// "String" appears everywhere, but Charset doesn't
		// hence having the charset constraint is useful as input to github!
		if (args.length > 4) {
			for (int i = 4; i < args.length; i++) {
				additionalKeywordConstraint.add(args[i]);
			}
		}

		Query query = parseQuery(input, additionalKeywordConstraint);

		printQuery(query);

		initUniqueFolderToSaveData(query, isPartitionedBySize);
		start = Instant.now();

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(getLabelFilePath()))) {
			writer.write("id" + "," + "label");
			writer.write("\n");
		} catch (IOException e) {

			e.printStackTrace();
			throw new RuntimeException(e);
		}

		processQuery(query, isPartitionedBySize);
		
		System.out.println("args were: " + Arrays.toString(args));
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

	private static void processQuery(Query query, boolean isSplitBySize) {

		String queryStr = query.toStringRequest();

		int lowerBound = 0, upperBound = 250, page, perPageLimit;
	
		page = 1;
		perPageLimit = 30;

		int id = 0;
		Optional<String> nextUrlRequest = Optional.empty();

		while (resolvedFiles.getResolvedFiles().size() < MAX_RESULT && id < MAX_TO_INSPECT && lowerBound < 250_000) {

			Response response;
			if (!nextUrlRequest.isPresent()) {
				// moving on to the next size partition!
				page = 1;
				
				String size = lowerBound + ".." + upperBound;
				response = handleCustomGithubRequest(queryStr, size, page, perPageLimit);
				
			
				lowerBound += 250;
				upperBound += 250;
			
				
				if (response.getTotalCount() == 0) {
					System.out.println("No item match with the query. Continuing");
					continue;
				}
			} else {
				response = handleGithubRequestWithUrl(nextUrlRequest.get());
			}
			
			JSONArray item = response.getItem();
			nextUrlRequest = response.getNextUrlRequest();
	
			Queue<String> data = new LinkedList<>();
			
			int stars = -1;
			for (int it = 0; it < item.length(); it++) {
				JSONObject instance = new JSONObject(item.get(it).toString());
				
				data.add(instance.getString("html_url"));
				
				JSONObject repo = instance.getJSONObject("repository");
				
				String repoUrl = repo.getString("url");
				stars = fetchStarGazers(repoUrl);
			}

			while (!data.isEmpty()) {
				String htmlUrl = data.remove();
				id++;

				System.out.println("ID: " + id);
				if (debug) {
					System.out.println("\tFile Url: " + htmlUrl);
				} 
				if (id % 50 == 0) {
					logTimingStatistics();
				}
				
				try {
					downloadAndResolveFile(id, htmlUrl, query, stars);
				} catch (IOException e) {
					e.printStackTrace();
					throw new RuntimeException(e);

				}
				int everyXtimes = debug ? 10 : 50;
				if (id % everyXtimes == 0) {
					System.out.println(
							"# Types of instances, unique at the file-level, seen (note- not necessarily actual API usages): " + Dedup.resolvable);
				}

				if (resolvedFiles.getResolvedFiles().size() >= MAX_RESULT && id >= MAX_TO_INSPECT) {
					break;
				}
			}
		}

		logTimingStatistics();
		System.out.println("===== Statistics about instances that we managed to resolve =====");
		System.out.println("<id>: <Number of similar copies found>");
		int total = 0;
		for (Entry<Integer, Boolean> entry : Dedup.canonicalCopiesResolvable.entrySet()) {
			if (!entry.getValue())
				continue;

			System.out.println("=== " + entry.getKey() + " : " + Dedup.canonicalCopiesCount.get(entry.getKey()));
			total += Dedup.canonicalCopiesCount.get(entry.getKey());
		}
		System.out.println("Total files: " + total);
		System.out.println("Total types of files: " + Dedup.resolvable);

		String metadataDirectory = DATA_LOCATION + "metadata/";
		if (!new File(metadataDirectory).exists()) {
			new File(metadataDirectory).mkdirs();
		}

		System.out.println("Writing metadata to " + metadataDirectory + "metadata.csv");
		System.out.println("\t\t and " + metadataDirectory + "metadata_locations.csv");
		System.out.println("\t\t and " + metadataDirectory + "metadata_stars.csv");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_LOCATION + "metadata/metadata.csv"))) {
			for (Entry<Integer, Boolean> entry : Dedup.canonicalCopiesResolvable.entrySet()) {
				if (!entry.getValue())
					continue;

				writer.write(entry.getKey() + "," + Dedup.canonicalCopiesCount.get(entry.getKey()) + "\n");

			}
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Unable to write metadata ...");
		}

		try (BufferedWriter writer = new BufferedWriter(
				new FileWriter(DATA_LOCATION + "metadata/metadata_locations.csv"))) {
			for (Entry<Integer, Boolean> entry : Dedup.canonicalCopiesResolvable.entrySet()) {
				if (!entry.getValue())
					continue;

				int i = 0;
				for (String url : Dedup.canonicalCopiesUrl.get(entry.getKey())) {
					writer.write(entry.getKey() + "," + i + "," + url + "\n");
					i++;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Unable to write metadata ...");
		}
		
		try (BufferedWriter writer = new BufferedWriter(
				new FileWriter(DATA_LOCATION + "metadata/metadata_stars.csv"))) {
			for (Entry<Integer, Boolean> entry : Dedup.canonicalCopiesResolvable.entrySet()) {
				if (!entry.getValue())
					continue;

				for (Entry<Integer, Integer> starEntry : starsOnRepo.entrySet()) {
					writer.write(entry.getKey() + "," + starEntry.getValue() + "\n");
				}
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

	public static void downloadAndResolveFile(int id, String htmlUrl, Query query, int stars) throws IOException {
		Optional<String> filePathOpt = downloadFile(htmlUrl, id);
		if (!filePathOpt.isPresent())
			return;

		String filePath = filePathOpt.get();

		List<String> lines = readLineByLine(filePath); // if fail due to some exception, then it will be empty
		boolean isClone = false;
		if (!lines.isEmpty() && Dedup.accept(id, htmlUrl, lines, stars, starsOnRepo)) {
			Optional<ResolvedFile> resolvedFileOpt = resolveFile(filePath, query);
			if (resolvedFileOpt.isPresent()) {
				ResolvedFile resolvedFile = resolvedFileOpt.get();

				resolvedFile.setUrl(htmlUrl);

				if (debug) {
					logTimingStatistics();
					System.out.println("\tURL: " + resolvedFile.getUrl());
					System.out.println("\tPath to File: " + resolvedFile.getPathFile());
					System.out.println("\tLine: " + resolvedFile.getLines());
					System.out.println("\tSnippet Code: ");
				}

				List<String> codes = getSnippetCode(resolvedFile.getPathFile(), resolvedFile.getLines());
				if (debug) {
					for (int j = 0; j < Math.min(codes.size(), 10); j++) {
						System.out.println(codes.get(j));
					}
				}

				resolvedFiles.add(resolvedFile);

				Dedup.indicateCanBeResolved(id);

				// move file to directory such that the package name is respected.
				// (usually useful for further analysis)
				String[] splitted = htmlUrl.split("/");
				String className = splitted[splitted.length - 1];
				String packageName = resolvedFile.getPackageName();
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
					System.out.println("\tmoved file to "
							+ new File(expectedFileLocation.toString() + "/" + className + ".txt"));
				} else {
					// no package
					Files.copy(new File(filePath).toPath(),
							new File(DATA_LOCATION + "files" + "/" + id + "." + className + ".txt").toPath());
					System.out.println("\tcopy file to "
							+ new File(DATA_LOCATION + "files" + "/" + id + "." + className + ".txt").toPath());
					
				}

				return;
			} else {
				
			}
		} else {
			isClone = true;
			// early return if this is a clone
			if (debug) {
				System.out.println("\t\tClone-like: " + id + " url=" + htmlUrl);
				
			} else {
				System.out.println("\t\tClone-like");
			}

			
		}

		// move file from DATA_LOCATION to DATA_LOCATION_FAILED
		if (debug) {
			System.out.println("\tmoving non-matching file");
			new File(filePath).renameTo(new File(DATA_LOCATION_FAILED + "files/" + id + ".txt"));
		} else {
			if (isClone) {
				new File(filePath).delete(); // save space.
			} else {
				new File(filePath).renameTo(new File(DATA_LOCATION_FAILED + "files/" + id + ".txt"));
			}
		}
		String oldFileDirectory = filePath.substring(0, filePath.lastIndexOf('/'));
		new File(oldFileDirectory).delete();

	
	}

	private static String getLabelFilePath() {
		return DATA_LOCATION + "labels.csv";
	}

	private static void logTimingStatistics() {
		Instant currentTime = Instant.now();
		long timeElapsed = Duration.between(start, currentTime).toMillis();
		long minutes = (timeElapsed / 1000) / 60;
		long seconds = (timeElapsed / 1000) % 60;
		long ms = (timeElapsed % 1000);
		System.out.println("\tTotal elapsed time: " + minutes + " minutes " + seconds + " seconds " + ms + "ms");
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

			System.out.print("\tdownload to " + pathFile + " .. from " + htmlUrl);

			FileOutputStream fileOutputStream = new FileOutputStream(pathFile);
			fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
			fileOutputStream.close();

			System.out.println(" succeeded!");

			return Optional.of(pathFile);

		} catch (FileNotFoundException e) {
			System.out.println("Can't download the github file");
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

	private static Optional<ResolvedFile> resolve(Query query, String pathFile) {
		File file = new File(pathFile);

		List<String> snippetCodes = new ArrayList<String>();
		List<Integer> lines = new ArrayList<Integer>();

		ResolvedFile resolvedFile = new ResolvedFile(query, "", "", lines, snippetCodes);
		try {
			List<String> addedJars = getNeededJars(file);
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
			System.out.println("===== Unable to parse");
			System.out.println("Exception is " + parseProblemException);
			System.out.println("File location: " + pathFile);
		} catch (IOException io) {
			System.out.println("=== IO Exception in Type Resolution ===");
			System.out.println("Exception is " + io);
			io.printStackTrace();
			System.out.println("File location: " + pathFile);
		} catch (RuntimeException runtimeException) {
			System.out.println("=== Runtime Exception in Type Resolution ===");
			System.out.println("Exception is " + runtimeException);
			runtimeException.printStackTrace();
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
						System.out.println(
								"\t\tfailed to match " + fullyQualifiedCtor + " against " + query.getFullyQualifiedClassName());
					}
				}

			} catch (UnsolvedSymbolException use) {
				System.out.println("\t\tunsolvedSymbolException in resolveFile");
				System.out.println("\t\tsymbol is " + use.getName());
			}

		}

		if (!isMethodMatch) {
			System.out.println("\t\tNo method match : " + query.getFullyQualifiedClassName());
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

				fullyQualifiedClassName = resolvedMethodDeclaration.getPackageName() + "." + resolvedMethodDeclaration.getClassName();

				// make some wild guesses
				
				if (resolvedMethodDeclaration.declaringType().isClass()
						|| resolvedMethodDeclaration.declaringType().isAnonymousClass()) {
					List<ResolvedReferenceType> interfaces = resolvedMethodDeclaration.declaringType().asClass()
							.getAllInterfaces();
					for (ResolvedReferenceType singleInterface : interfaces) {
						String fullyQualifiedInterfaceMethodName = singleInterface.getQualifiedName(); // + "#"+ mce.getNameAsString();
						fullyQualifiedInterfaceNames.add(fullyQualifiedInterfaceMethodName);
					}
				} else if (resolvedMethodDeclaration.declaringType().isInterface()) {
					List<ResolvedReferenceType> interfaces = resolvedMethodDeclaration.declaringType().asInterface()
							.getAllInterfacesExtended();
					for (ResolvedReferenceType singleInterface : interfaces) {
						String fullyQualifiedInterfaceMethodName = singleInterface.getQualifiedName(); // + "#" + mce.getNameAsString();
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

	private static Query parseQuery(String s, List<String> additionalKeywordConstraint) {
		s = s.replace(" ", "");

		int hashLocation = s.indexOf('#');
		int leftBracketLocation = s.indexOf('(');
		int rightBracketLocation = s.indexOf(')');
		if (hashLocation == -1) {
			System.out.println("Your query isn't accepted");
			System.out.println("Query Format: " + "method");
			System.out.println("Example: " + "android.app.Notification.Builder#addAction(argument, ...)");

			throw new RuntimeException("wrong query format!");
		}
		String fullyQualifiedClassName = s.substring(0, hashLocation);
		String method = s.substring(hashLocation + 1, leftBracketLocation);
		String args = s.substring(leftBracketLocation + 1, rightBracketLocation);

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
		query.setAdditionalKeywords(additionalKeywordConstraint);
		return query;
	}

	private static void initUniqueFolderToSaveData(Query query, boolean isSplitBySize) {

		String folderName = query.getFullyQualifiedClassName() + "__" + query.getMethod() + "__"
				+ query.getArguments().size();
		
		if (!query.getAdditionalKeywords().isEmpty()) {
			folderName += query.getAdditionalKeywords();
		}
		folderName += "_" + isSplitBySize;

		makeFileResolutionLocation(folderName);

		makeFailedFilesLocation(folderName);

		File jarFolder = new File(JARS_LOCATION);
		if (!jarFolder.exists()) {
			jarFolder.mkdir();
		}

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
			System.out.println("One should delete the old collected files before rerunning this");
			throw new RuntimeException(DATA_LOCATION + "files/" + " seems to already exist. One should delete them before rerunning this.");
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
			if (responseCode == RESPONSE_OK) {
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
			} else if (responseCode == BAD_CREDENTIAL) {
				System.out.println("Authorization problem");
				System.out.println("Please read the readme file!");
				System.out.println("Github message: " + (new JSONObject(request.body())).getString("message"));
				System.exit(-1);
			} else if (responseCode == ABUSE_RATE_LIMITS) {
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
			} else if (responseCode == UNPROCESSABLE_ENTITY) {
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

		} while (!response_ok && responseCode != UNPROCESSABLE_ENTITY);

		synchronizedFeeder.releaseToken(token);

		return response;
	}

	static Map<String, Integer> knownStars = new HashMap<>();
	
	private static int fetchStarGazers(String url) {
		if (knownStars.containsKey(url)) {
			return knownStars.get(url);
		}

		boolean response_ok = false;
		int response = -1;
		int responseCode;

		// encode the space into %20
		url = url.replace(" ", "%20");
		GithubToken token = synchronizedFeeder.getAvailableGithubToken();

		do {
			HttpRequest request = HttpRequest.get(url, false).authorization("token " + token.getToken());
			System.out.println();
			System.out.println("Request: " + request);

			responseCode = request.code();
			if (responseCode == RESPONSE_OK) {
				JSONObject body = new JSONObject(request.body());
				response = body.getInt("stargazers_count");
				response_ok = true;
		
			} else if (responseCode == BAD_CREDENTIAL) {
				System.out.println("Authorization problem");
				System.out.println("Please read the readme file!");
				System.out.println("Github message: " + (new JSONObject(request.body())).getString("message"));
				System.exit(-1);
			} else if (responseCode == ABUSE_RATE_LIMITS) {
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
			} else if (responseCode == UNPROCESSABLE_ENTITY) {
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

		} while (!response_ok && responseCode != UNPROCESSABLE_ENTITY);

		synchronizedFeeder.releaseToken(token);

		
		knownStars.put(url, response);
		return response;
	}
	
	private static Response handleCustomGithubRequest(String query, String size, int page,
			int per_page_limit) {
		// The size range is exclusive

		Response response = new Response();

		String url = endpoint + "?" + PARAM_QUERY + "=" + query + "+size:" + size + "+in:file+language:java" + "&" + PARAM_PAGE + "="
				+ page + "&" + PARAM_PER_PAGE + "=" + per_page_limit ;// +"&" + PARAM_SORT + "=indexed";
		response = handleGithubRequestWithUrl(url);

		return response;
	}

	private static Optional<String> getNextLinkFromResponse(String linkHeader) {

		String next = null;

		if (linkHeader != null) {
			String[] links = linkHeader.split(DELIM_LINKS);
			for (String link : links) {
				String[] segments = link.split(DELIM_LINK_PARAM);
				if (segments.length < 2)
					continue;

				String linkPart = segments[0].trim();
				if (!linkPart.startsWith("<") || !linkPart.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
					continue;
				linkPart = linkPart.substring(1, linkPart.length() - 1);

				for (int i = 1; i < segments.length; i++) {
					String[] rel = segments[i].trim().split("="); //$NON-NLS-1$
					if (rel.length < 2 || !META_REL.equals(rel[0]))
						continue;

					String relValue = rel[1];
					if (relValue.startsWith("\"") && relValue.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
						relValue = relValue.substring(1, relValue.length() - 1);

					if (META_NEXT.equals(relValue))
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

	private static List<String> getNeededJars(File file) {
		List<String> jarsPath = new ArrayList<String>();
		TypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false),
				new JavaParserTypeSolver(new File("src/main/java")));
		StaticJavaParser.getConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));

		// list of specific package imported
		List<String> importedPackages = new ArrayList<String>();
		try {
			CompilationUnit cu = StaticJavaParser.parse(file);
			cu.findAll(Name.class).forEach(mce -> {
				String[] names = mce.toString().split("[.]");
				if (names.length >= 2) { // filter some wrong detected import like Override, SupressWarning
					if (importedPackages.isEmpty()) {
						importedPackages.add(mce.toString());
					} else {
						boolean isAlreadyDefined = false;
						for (int i = 0; i < importedPackages.size(); i++) {
							if (importedPackages.get(i).contains(mce.toString())) {
								isAlreadyDefined = true;
								break;
							}
						}
						if (!isAlreadyDefined) {
							importedPackages.add(mce.toString());
						}
					}
				}
			});
		} catch (FileNotFoundException e) {
			System.out.println("EXCEPTION");
			System.out.println("File not found!");
		} catch (ParseProblemException parseException) {
			return jarsPath;
		}

		// filter importedPackages
		// remove the project package and java predefined package
		List<String> neededPackages = new ArrayList<String>();
		if (importedPackages.size() > 0) {
			String qualifiedName = importedPackages.get(0);
			String[] names = qualifiedName.split("[.]");
			String projectPackage = names[0].toString();
			for (int i = 1; i < importedPackages.size(); i++) { // the first package is skipped
				qualifiedName = importedPackages.get(i);
				names = qualifiedName.split("[.]");
				String basePackage = names[0];
				if (!basePackage.equals(projectPackage) && !basePackage.equals("java") && !basePackage.equals("javax")
						&& !basePackage.equals("Override")) {
					neededPackages.add(importedPackages.get(i));
				}
			}
		}

		List<MavenPackage> mavenPackages = new ArrayList<MavenPackage>();

		// get the groupId and artifactId from the package qualified name
		for (int i = 0; i < neededPackages.size(); i++) {
			String qualifiedName = neededPackages.get(i);
			MavenPackage mavenPackage = getMavenPackageArtifact(qualifiedName);

			if (!mavenPackage.getId().equals("")) { // handle if the maven package is not exist
				// filter if the package is used before
				boolean isAlreadyUsed = false;
				for (int j = 0; j < mavenPackages.size(); j++) {
					MavenPackage usedPackage = mavenPackages.get(j);
					if (mavenPackage.getGroupId().equals(usedPackage.getGroupId())
							&& mavenPackage.getArtifactId().equals(usedPackage.getArtifactId())) {
						isAlreadyUsed = true;
					}
				}
				if (!isAlreadyUsed) {
					mavenPackages.add(mavenPackage);
				}
			}
		}

		for (int i = 0; i < mavenPackages.size(); i++) {
			String pathToJar = downloadMavenJar(mavenPackages.get(i).getGroupId(),
					mavenPackages.get(i).getArtifactId());
			if (!pathToJar.equals("")) {
				// System.out.println("Downloaded: " + pathToJar);
				jarsPath.add(pathToJar);
			}
		}

		return jarsPath;
	}

	// download the latest package by groupId and artifactId
	private static String downloadMavenJar(String groupId, String artifactId) {
		String path = JARS_LOCATION + artifactId + "-latest.jar";
		String url = "http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=" + groupId
				+ "&a=" + artifactId + "&v=LATEST";
		File jarFile = new File(path);

		if (!jarFile.exists()) {
			// Equivalent command conversion for Java execution
			String[] command = { "curl", "-L", url, "-o", path };

			ProcessBuilder process = new ProcessBuilder(command);
			Process p;
			try {
				p = process.start();
				BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
				StringBuilder builder = new StringBuilder();
				String line = null;
				while ((line = reader.readLine()) != null) {
					builder.append(line);
					builder.append(System.getProperty("line.separator"));
				}
				String result = builder.toString();
				System.out.print(result);

			} catch (IOException e) {
				System.out.print("error");
				e.printStackTrace();
			}
		}

		return path;

	}

	private static MavenPackage getMavenPackageArtifact(String qualifiedName) {

		MavenPackage mavenPackageName = new MavenPackage();

		String url = "https://search.maven.org/solrsearch/select?q=fc:" + qualifiedName + "&wt=json";

		HttpRequest request = HttpRequest.get(url, false);

		// handle response
		int responseCode = request.code();
		if (responseCode == RESPONSE_OK) {
			JSONObject body = new JSONObject(request.body());
			JSONObject response = body.getJSONObject("response");
			int numFound = response.getInt("numFound");
			JSONArray mavenPackages = response.getJSONArray("docs");
			if (numFound > 0) {
				mavenPackageName.setId(mavenPackages.getJSONObject(0).getString("id")); // set the id
				mavenPackageName.setGroupId(mavenPackages.getJSONObject(0).getString("g")); // set the first group id
				mavenPackageName.setArtifactId(mavenPackages.getJSONObject(0).getString("a")); // set the first artifact
																								// id
				mavenPackageName.setVersion(mavenPackages.getJSONObject(0).getString("v")); // set the first version id
			}
		} else {
			System.out.println("Response Code: " + responseCode);
			System.out.println("Response Body: " + request.body());
			System.out.println("Response Headers: " + request.headers());
		}

		return mavenPackageName;
	}

}
