package com.project.githubsearch;

import java.util.List;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.project.githubsearch.model.SynchronizedTypeSolver;

public class Scratch2 {
	
	public static void main(String... args ) {
		
		Object[] links = new Object[] {100L, 200L};
		
		for (Object link : links) {
			System.out.println("first loop:");
            putLong((Long) link);
        }
		
		for (Object link : links) {
			System.out.println("first loop:");
            putLong((long) link);
        }
	}
	
	public static void putLong(Long i) {
		System.out.println("Long");
	}
	
	public static void putLong(long i) {
		System.out.println("long");
	}


}
