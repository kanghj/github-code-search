package com.project.githubsearch.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Name;
import com.google.common.base.Charsets;

/**
 * Takes test_instances.csv as input and produces to_annotate.csv as output
 * @author kanghongjin
 *
 */
public class CreateFileForLabelling {
	
	static List<String> projects = Arrays.asList(
			"commons-bcel",
			"commons-math",
			"commons-text",
			"curator",
			"directory-fortress-core",
			"h2database",
			"itextpdf",
			"jackrabbit",
			"jfreechart",
			"pdfbox",
			"santuario-java",
			"swingx",
			"wildfly-elytron",
			"xmlgraphics-fop",
			"bigtop",
			"commons-lang"
			);
	
	static Map<String, String> projectUrl = new HashMap<>();
	static {
		projectUrl.put("commons-bcel","https://github.com/apache/commons-bcel/blob/5cc4b163");
		projectUrl.put("commons-math","https://github.com/apache/commons-math/blob/1abe3c769");
		projectUrl.put("commons-text","https://github.com/apache/commons-text/blob/7d2b511");
		projectUrl.put("curator","https://github.com/apache/curator/blob/2af84b9f");
		projectUrl.put("directory-fortress-core","https://github.com/apache/directory-fortress-core/blob/a7ab0c01");
		projectUrl.put("h2database","https://github.com/h2database/h2database/blob/0ea0365c2");
		projectUrl.put("itextpdf","https://github.com/itext/itextpdf/blob/2d5b6a212");
		projectUrl.put("jackrabbit","https://github.com/apache/jackrabbit/blob/da3fd4199");
		projectUrl.put("jfreechart","https://github.com/jfree/jfreechart/blob/893f9b15");
		projectUrl.put("pdfbox","https://github.com/apache/pdfbox/blob/72249f6ff");
		projectUrl.put("santuario-java","https://github.com/apache/santuario-java/blob/3832bd83");
		projectUrl.put("swingx","https://github.com/ebourg/swingx/blob/820656c");
		projectUrl.put("wildfly-elytron","https://github.com/wildfly-security/wildfly-elytron/blob/a73bbba0f0");
		projectUrl.put("xmlgraphics-fop","https://github.com/apache/xmlgraphics-fop/blob/1942336d7");
		projectUrl.put("bigtop","https://github.com/apache/bigtop/blob/c9cb18fb");
		projectUrl.put("commons-lang","https://github.com/apache/commons-lang/blob/0820c4c89");

	}
	
	static String snippet = "";
	static int lineNumber = 0;

	public static void main(String... args) throws IOException {
//		List<String> lines = Files.lines(Paths.get("/Users/kanghongjin/repos/graph_classifier/test_instances.csv")).collect(Collectors.toList());
		File file = new File("/Users/kanghongjin/repos/graph_classifier/test_instances.csv");
		CSVParser parser = CSVParser.parse(file.toPath(), Charsets.UTF_8, CSVFormat.RFC4180);

		try (BufferedWriter writer = new BufferedWriter(new FileWriter("/Users/kanghongjin/repos/graph_classifier/annotation_prep/to_annotate_for_stefanus.csv"));
				CSVPrinter printer = CSVFormat.DEFAULT.withHeader( "API",  "method", "github url", "label (M/C)").print(writer)) {
			

			int i = 0;
			for (CSVRecord line : parser) {
				if (i <= 430) {
					i ++;
					continue;
				}
				i++;
				String locationStr = line.get(0);
				String API = line.get(1);

				String path = locationStr.split(" - ")[0];
				
				String project = "";
				for (String possibleProject : projects) {
					if (path.contains(possibleProject)) {
						project = possibleProject;
						break;
					}
				}
				
				String pathWithoutmyHomeDirectory = path.split("repos_for_misuses/")[1];
				
				String method = locationStr.split(" - ")[1];

				
				String[] splitted = method.split("\\.")[1].split("#");
				String methodName = splitted[0];
				List<String> methodParams = new ArrayList<>(Arrays.asList(splitted).subList(1, splitted.length));

				snippet = "";
				CompilationUnit cu = StaticJavaParser.parse(new File(path));
				cu.findAll(MethodDeclaration.class).forEach(md -> {
					if (!md.getName().toString().equals(methodName)) {
						return;
					}
					if (md.getParameters().size() != methodParams.size()) {
						return;
					}
					snippet = md.toString();
					lineNumber = md.getName().getBegin().get().line;


						
				});
				
				if (snippet.isEmpty()) {
					// maybe its a constructor
					cu.findAll(ConstructorDeclaration.class).forEach(md -> {
						
						if (md.getParameters().size() != methodParams.size()) {
							return;
						}
						snippet = md.toString();
						lineNumber = md.getName().getBegin().get().line;
					});
				}
				
				int projectIndexPosition = path.indexOf(project) + project.length();
				List<String> pathSplitted = Arrays.asList(path.substring(projectIndexPosition).split("/"));
				String pathOnGithub = projectUrl.get(project) + "/" + String.join("/", pathSplitted.subList(1, pathSplitted.size()));
				
				String lineAnchor = "#L" + lineNumber;

				printer.printRecord( API, method, pathOnGithub + lineAnchor, "");
			}
		}

	}
}
