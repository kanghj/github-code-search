package com.project.githubsearch;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.kevinsawicki.http.HttpRequest;
import com.project.githubsearch.model.MavenPackage;
import com.project.githubsearch.model.Query;
import com.project.githubsearch.model.ResolvedFiles;
import com.project.githubsearch.model.ResolvedFile;
import com.project.githubsearch.model.Response;
import com.project.githubsearch.model.SynchronizedData;
import com.project.githubsearch.model.SynchronizedFeeder;
import com.project.githubsearch.model.SynchronizedTypeSolver;
import com.project.githubsearch.model.GithubToken;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
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
	// please make sure that the number of thread is equal with the number of tokens
	private static final int NUMBER_THREADS = 3;
	private static final int NUMBER_CORE = 1;

	// parameter for the request
	private static final String PARAM_QUERY = "q"; //$NON-NLS-1$
	private static final String PARAM_PAGE = "page"; //$NON-NLS-1$
	private static final String PARAM_PER_PAGE = "per_page"; //$NON-NLS-1$

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
	private static final int MAX_RESULT = 10; // 30 for local testing; set to 100 for server testing; then 500 for the
												// final run

	// folder location to save the downloaded files and jars
	private static String DATA_LOCATION = "src/main/java/com/project/githubsearch/data/";
	private static String DATA_LOCATION_FAILED = "src/main/java/com/project/githubsearch/failed_data/";
	private static final String JARS_LOCATION = "src/main/java/com/project/githubsearch/jars/";

	private static final String endpoint = "https://api.github.com/search/code";

	private static SynchronizedData synchronizedData = new SynchronizedData();
	private static SynchronizedFeeder synchronizedFeeder = new SynchronizedFeeder();
	private static ResolvedFiles resolvedFiles = new ResolvedFiles();
	private static SynchronizedTypeSolver synchronizedTypeSolver = new SynchronizedTypeSolver();

	private static Instant start;
	private static Instant currentTime;

	public static void main(String[] args) {
//        Scanner scanner = new Scanner(System.in);

		// TODO: i don't need interactive...
		// just use args
		// and just one query
//        System.out.println("Please Input Your Query: ");
//        String input = scanner.nextLine();
//        scanner.close();

		String input = args[0];
		Query query = parseQuery(input);

		printQuery(query);
		initUniqueFolderToSaveData(query);
		start = Instant.now();
		processQuery(query);

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

	private static void processQuery(Query query) {

		String queryStr = query.toStringRequest();

		int lower_bound, upper_bound, page, per_page_limit;
		lower_bound = 0;
		upper_bound = 384000;
		page = 1;
		per_page_limit = 30;

		Response response = handleCustomGithubRequest(queryStr, lower_bound, upper_bound, page, per_page_limit);
		if (response.getTotalCount() == 0) {
			System.out.println("No item match with the query");
			return;
		}

		JSONArray item = response.getItem();
		String nextUrlRequest = response.getNextUrlRequest();

		Queue<String> data = new LinkedList<>();
		for (int it = 0; it < item.length(); it++) {
			JSONObject instance = new JSONObject(item.get(it).toString());
			data.add(instance.getString("html_url"));
		}

		int id = 0;

		while (resolvedFiles.getResolvedFiles().size() < MAX_RESULT) {
			if (data.size() < (2 * NUMBER_CORE)) {
				response = handleGithubRequestWithUrl(nextUrlRequest);
				item = response.getItem();
				nextUrlRequest = response.getNextUrlRequest();
				for (int it = 0; it < item.length(); it++) {
					JSONObject instance = new JSONObject(item.get(it).toString());
					data.add(instance.getString("html_url"));
				}
			}

			// System.out.println("=====================");
			// System.out.println("Without multi-threading");
			// System.out.println("=====================");
			id = id + 1;
			String htmlUrl = data.remove();
			System.out.println();
			System.out.println("ID: " + id);
			System.out.println("File Url: " + htmlUrl);
			downloadAndResolveFile(id, htmlUrl, query);

			// System.out.println("=====================");
			// System.out.println("Multi-threading start");
			// System.out.println("=====================");

			// ExecutorService executor = Executors.newFixedThreadPool(NUMBER_THREADS);

			// for (int i = 0; i < NUMBER_CORE; i++) {
			// String htmlUrl = data.remove();
			// id = id + 1;
			// System.out.println("id: " + id);
			// System.out.println("html url: " + htmlUrl);
			// Runnable worker = new RunnableResolver(id, htmlUrl, queries);
			// executor.execute(worker);
			// }

			// executor.shutdown();
			// // Wait until all threads are finish
			// while (!executor.isTerminated()) {}

			// System.out.println("===================");
			// System.out.println("Multi-threading end");
			// System.out.println("===================");

		}

	}

	public static void downloadAndResolveFile(int id, String htmlUrl, Query query) {
		boolean isDownloaded = downloadFile(htmlUrl, id);
		if (!isDownloaded) return;

		Optional<ResolvedFile> resolvedFileOpt = resolveFile(id, query);
		if (resolvedFileOpt.isPresent()) {
			ResolvedFile resolvedFile = resolvedFileOpt.get();
			currentTime = Instant.now();
			
			logTimingStatistics();
			
			resolvedFile.setUrl(htmlUrl);
			
			System.out.println("URL: " + resolvedFile.getUrl());
			System.out.println("Path to File: " + resolvedFile.getPathFile());
			System.out.println("Line: " + resolvedFile.getLines());
			System.out.println("Snippet Codes: ");
			
			List<String> codes = getSnippetCode(resolvedFile.getPathFile(), resolvedFile.getLines());
			for (int j = 0; j < codes.size(); j++) {
				System.out.println(codes.get(j));
			}
			
			resolvedFiles.add(resolvedFile);
		} else {
			// move file from DATA_LOCATION to DATA_LOCATION_FAILED
			String pathFile = new String(DATA_LOCATION + "files/" + id + ".txt");
			new File(pathFile).renameTo(new File(DATA_LOCATION_FAILED + "files/" + id + ".txt"));
			System.out.println("moving file");
			
		}
	}

	private static void logTimingStatistics() {
		long timeElapsed = Duration.between(start, currentTime).toMillis();
		long minutes = (timeElapsed / 1000) / 60;
		long seconds = (timeElapsed / 1000) % 60;
		long ms = (timeElapsed % 1000);
		System.out.println("Elapsed time from start: " + minutes + " minutes " + seconds + " seconds " + ms + "ms");
	}

	public static class RunnableResolver implements Runnable {
		private final int id;
		private final String htmlUrl;
		private final Query query;

		RunnableResolver(int id, String htmlUrl, Query query) {
			this.id = id;
			this.htmlUrl = htmlUrl;
			this.query = query;
		}

		@Override
		public void run() {
			downloadAndResolveFile(id, htmlUrl, query);
		}
	}

	private static boolean downloadFile(String htmlUrl, int fileId) {
		// convert html url to downloadable url
		// based on my own analysis
		String downloadableUrl = convertHTMLUrlToDownloadUrl(htmlUrl);

		// using it to make a unique name
		// replace java to txt for excluding from maven builder
		String fileName = fileId + ".txt";

		// System.out.println();
		// System.out.println("Downloading the file: " + (fileId));
		// System.out.println("HTML Url: " + htmlUrl);

		boolean finished = false;

		try {
			// download file from url
			URL url;
			url = new URL(downloadableUrl);
			ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
			String pathFile = new String(DATA_LOCATION + "files/" + fileName);
			FileOutputStream fileOutputStream = new FileOutputStream(pathFile);
			fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
			fileOutputStream.close();
			finished = true;
		} catch (FileNotFoundException e) {
			System.out.println("Can't download the github file");
			System.out.println("File not found!");
		} catch (MalformedURLException e) {
			System.out.println("Malformed URL Exception while downloading!");
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("Can't save the downloaded file");
		}

		return finished;
	}

	private static Optional<ResolvedFile> resolveFile(int fileId, Query query) {

		return resolve(query, new String(DATA_LOCATION + "files/" + fileId + ".txt"));

	}

	private static Optional<ResolvedFile> resolve(Query query, String pathFile) {
		File file = new File(pathFile);

		List<String> snippetCodes = new ArrayList<String>();
		List<Integer> lines = new ArrayList<Integer>();

		ResolvedFile resolvedFile = new ResolvedFile(query, "", "", lines, snippetCodes);
		// System.out.println();
		try {
			List<String> addedJars = getNeededJars(file);
			for (int i = 0; i < addedJars.size(); i++) {
				try {
					TypeSolver jarTypeSolver = JarTypeSolver.getJarTypeSolver(addedJars.get(i));
					synchronizedTypeSolver.add(jarTypeSolver);
				} catch (Exception e) {
					System.out.println("=== Package corrupt! ===");
					System.out.println("Corrupted jars: " + addedJars.get(i));
					System.out.println("Please download the latest jar manually from maven repository!");
					System.out.println("File location: " + file.toString());
				}
			}
			StaticJavaParser.getConfiguration()
					.setSymbolResolver(new JavaSymbolSolver(synchronizedTypeSolver.getTypeSolver()));
			CompilationUnit cu;
			cu = StaticJavaParser.parse(file);

			boolean isMethodMatch = false;
			boolean isResolved = false;
			boolean isFullyQualifiedClassNameMatch = false;

			List<MethodCallExpr> methodCallExprs = cu.findAll(MethodCallExpr.class);
			List<String> methodCallNames = new ArrayList<>();
			List<String> closeMethodCallNames = new ArrayList<>(); // names that only differ because the FQN check failed
			
			
			for (int j = 0; j < methodCallExprs.size(); j++) {
				MethodCallExpr mce = methodCallExprs.get(j);
				
				methodCallNames.add(mce.getName().toString() + ":" + mce.getArguments().size());
				
				if (!mce.getName().toString().equals(query.getMethod()) 
						|| mce.getArguments().size() != query.getArguments().size()) {
					// ignore if different name or different number of arguments
					continue;
				}
				
				isMethodMatch = true;
				try {
					ResolvedMethodDeclaration resolvedMethodDeclaration = mce.resolve();
					
					
					String fullyQualifiedClassName = resolvedMethodDeclaration.getPackageName() + "."
							+ resolvedMethodDeclaration.getClassName();
					
					// make some wild guesses
					List<String> fullyQualifiedInterfaceNames = new ArrayList<>();
					if (resolvedMethodDeclaration.declaringType().isClass() || resolvedMethodDeclaration.declaringType().isAnonymousClass()) {
						List<ResolvedReferenceType> interfaces = resolvedMethodDeclaration.declaringType().asClass().getAllInterfaces();
						for (ResolvedReferenceType singleInterface : interfaces) {
							String fullyQualifiedInterfaceMethodName = singleInterface.getQualifiedName() + "#" + mce.getNameAsString();
							fullyQualifiedInterfaceNames.add(fullyQualifiedInterfaceMethodName);
						}
					} else if (resolvedMethodDeclaration.declaringType().isInterface()){
						List<ResolvedReferenceType> interfaces = resolvedMethodDeclaration.declaringType().asInterface().getAllInterfacesExtended();
						for (ResolvedReferenceType singleInterface : interfaces) {
							String fullyQualifiedInterfaceMethodName = singleInterface.getQualifiedName() + "#" + mce.getNameAsString();
							fullyQualifiedInterfaceNames.add(fullyQualifiedInterfaceMethodName);
						}
						
					}
					
					
					
					isResolved = true;
			
					
					// argument type match doesn't deal with generics very well, i think
					// we'll just count the number of parameters then
                    if (// isArgumentTypeMatch &&
                    		fullyQualifiedClassName.equals(query.getFullyQualifiedClassName())
                    		|| fullyQualifiedInterfaceNames.contains(query.getFullyQualifiedClassName())) {
                    	
                    	isFullyQualifiedClassNameMatch = true;
                    	
                        lines.add(mce.getBegin().get().line);
                    } else {
                    	closeMethodCallNames.add(fullyQualifiedClassName + "#" + mce.getNameAsString());
                    	closeMethodCallNames.addAll(fullyQualifiedInterfaceNames);
                    }
                    
				} catch (UnsolvedSymbolException use) {
					System.out.println("unsolvedSymbolException in resolveFile");
					System.out.println("symbol is " + use.getName());
				}
			
			}

			if (!isMethodMatch) {
				System.out.println("No method match : " + query.getMethod());
				System.out.println("names in file: " + methodCallNames);
			}
			if (!isResolved) {
				System.out.println("Can't resolve :" + query.getMethod());
			}
			if (!isFullyQualifiedClassNameMatch) {
				System.out.println("fully qualified names are " + closeMethodCallNames);
			}

			if (isMethodMatch && isResolved && isFullyQualifiedClassNameMatch) {
				resolvedFile.setPathFile(pathFile);
				resolvedFile.setLines(lines);
				resolvedFile.setCodes(getSnippetCode(pathFile, lines));
				System.out.println("=== SUCCESS ===");
				return Optional.of(resolvedFile);
			} else {
				return Optional.empty();
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

	private static void printQuery(Query query) {
		System.out.println("============");
		System.out.println("Your Queries");
		System.out.println("============");

		System.out.println("Query " + ": " + query);

	}

	private static Query parseQuery(String s) {
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

		return query;
	}

	private static void initUniqueFolderToSaveData(Query query) {

		String folderName = query.getFullyQualifiedClassName() + "__" + query.getMethod();

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
		if (!files.exists()) {
			files.mkdir();
		}
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

	public static class URLRunnable implements Runnable {
		private final String url;

		URLRunnable(String query, int lower_bound, int upper_bound, int page, int per_page_limit) {
			upper_bound++;
			lower_bound--;
			String size = lower_bound + ".." + upper_bound;
			this.url = endpoint + "?" + PARAM_QUERY + "=" + query + "+in:file+language:java" + "&" + PARAM_PAGE + "="
					+ page + "&" + PARAM_PER_PAGE + "=" + per_page_limit;
		}

		@Override
		public void run() {
			Response response = handleGithubRequestWithUrl(url);
			JSONArray item = response.getItem();
			// System.out.println("Request: " + response.getUrlRequest());
			// System.out.println("Number items: " + item.length());
			synchronizedData.addArray(item);
		}
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
				}
				response_ok = true;
			} else if (responseCode == BAD_CREDENTIAL) {
				System.out.println("Authorization problem");
				System.out.println("Please read the readme file!");
				System.out.println("Github message: " + (new JSONObject(request.body())).getString("message"));
				System.exit(-1);
			} else if (responseCode == ABUSE_RATE_LIMITS) {
				System.out.println("Abuse Rate Limits");
				// retry current progress after wait for a minute
				String retryAfter = request.header("Retry-After");
				try {
					int sleepTime = 0; // wait for a while
					if (retryAfter.isEmpty()) {
						sleepTime = 1;
					} else {
						sleepTime = new Integer(retryAfter).intValue();
					}
					System.out.println("Retry-After: " + sleepTime);
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
				System.exit(-1);
			}

		} while (!response_ok && responseCode != UNPROCESSABLE_ENTITY);

		synchronizedFeeder.releaseToken(token);

		return response;
	}

	private static Response handleCustomGithubRequest(String query, int lower_bound, int upper_bound, int page,
			int per_page_limit) {
		// The size range is exclusive
		upper_bound++;
		lower_bound--;
		String size = lower_bound + ".." + upper_bound; // lower_bound < size < upper_bound

		String url;
		Response response = new Response();

		url = endpoint + "?" + PARAM_QUERY + "=" + query + "+in:file+language:java" + "&" + PARAM_PAGE + "=" + page
				+ "&" + PARAM_PER_PAGE + "=" + per_page_limit;
		response = handleGithubRequestWithUrl(url);

		return response;
	}

	private static String getNextLinkFromResponse(String linkHeader) {

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
		return next;
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
