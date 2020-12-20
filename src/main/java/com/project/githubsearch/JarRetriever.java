package com.project.githubsearch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.kevinsawicki.http.HttpRequest;
import com.project.githubsearch.model.MavenPackage;

public class JarRetriever {
	
	public static final String JARS_LOCATION = "src/main/java/com/project/githubsearch/jars/";
	
	/**
	 * Download the jar file matching the package name
	 * @param file
	 * @return
	 * @throws IOException 
	 */
	public static String getLikelyJarOf(File file) throws IOException {
		List<String> lines = Files.readAllLines(file.toPath());
		String matchedPackage = null;
		for (String line : lines) {
			if (line.startsWith("package ")) {
				matchedPackage = line.split("package ")[1]; 
				
				break; 
			}
			
		}
		
		if (matchedPackage == null) {
			// may match toy projects without a package
			return null;
		}
		
		
		MavenPackage mavenPackage = getMavenPackageArtifact(matchedPackage);

		if (mavenPackage.getId().equals("")) { // handle if the maven package is not exist
			
			// bad
			return null;
		
		}
		
		String pathToJar = downloadMavenJar(mavenPackage.getGroupId(), mavenPackage.getArtifactId());
		if (!pathToJar.equals("")) {
			 System.out.println("Downloaded: " + pathToJar);
			
		}

		return pathToJar;		
	}
	
	public static List<String> getNeededJars(File file) {
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

			for (int i = 0; i < importedPackages.size(); i++) { 
				qualifiedName = importedPackages.get(i);
				names = qualifiedName.split("[.]");
				String basePackage = names[0];
				if (//!basePackage.equals(projectPackage) && 
						!basePackage.equals("java") && !basePackage.equals("javax")
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
			if (!pathToJar.isEmpty()) { 
				System.out.println();
				System.out.println("Downloaded: " + pathToJar);
				jarsPath.add(pathToJar);
			}
		}
		return jarsPath;
	}

	// download the latest package by groupId and artifactId
	public static String downloadMavenJar(String groupId, String artifactId) {
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

	public static MavenPackage getMavenPackageArtifact(String qualifiedName) {

		MavenPackage mavenPackageName = new MavenPackage();

		String url = "https://search.maven.org/solrsearch/select?q=fc:" + qualifiedName + "&wt=json";

		HttpRequest request = HttpRequest.get(url, false);

		// handle response
		int responseCode = request.code();
		if (responseCode == Utils.RESPONSE_OK) {
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
