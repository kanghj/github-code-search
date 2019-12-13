package com.project.githubsearch;

import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.project.githubsearch.model.SynchronizedTypeSolver;

public class Scratch {
	
	public static void main(String... args ) {
		StaticJavaParser.getConfiguration()
			.setSymbolResolver(new JavaSymbolSolver(new SynchronizedTypeSolver().getTypeSolver()));
		CompilationUnit cu = StaticJavaParser.parse("import java.util.HashMap;\n" + 
				"import java.util.Map;\n" + 
				"\n" + 
				"public class Test {\n" + 
				"	public static void main() {\n" + 
				"		Map<String, Integer> map = new HashMap<>();\n" + 
				"		map.entrySet().iterator().next();\n" + 
				"	}\n" + 
				"\n" + 
				"}\n" + 
				"");
		
		List<MethodCallExpr> methodCallExprs = cu.findAll(MethodCallExpr.class);
		for (int j = 0; j < methodCallExprs.size(); j++) {
			MethodCallExpr mce = methodCallExprs.get(j);
			ResolvedMethodDeclaration resolvedMethodDeclaration = mce.resolve();
			System.out.println(resolvedMethodDeclaration.getPackageName() + "." + resolvedMethodDeclaration.getClassName() + " of " + mce.getNameAsString());
		}
	}

}
