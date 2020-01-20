package com.project.githubsearch;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.project.githubsearch.model.Query;
import com.project.githubsearch.model.ResolvedFile;
import com.project.githubsearch.model.SynchronizedTypeSolver;

public class ResolverDebug {

	private static SynchronizedTypeSolver synchronizedTypeSolver = new SynchronizedTypeSolver();

	public static void main(String... strings) throws FileNotFoundException {

		StaticJavaParser.getConfiguration()
				.setSymbolResolver(new JavaSymbolSolver(synchronizedTypeSolver.getTypeSolver()));

		CompilationUnit cu = StaticJavaParser.parse(new File("/Users/kanghongjin/Downloads/IntroMap.java"));

		Optional<PackageDeclaration> packageName = cu.getPackageDeclaration();

		boolean isMethodMatch = false;
		boolean isResolved = false;
		boolean isFullyQualifiedClassNameMatch = false;

		// HJ: for debugging
		List<String> methodCallNames = new ArrayList<>();
		List<String> closeMethodCallNames = new ArrayList<>(); // names that only differ because the FQN check
																// failed

		NodeList<TypeDeclaration<?>> typs = cu.getTypes();

		for (TypeDeclaration typ : typs) {
			List<MethodCallExpr> methodCallExprs = typ.findAll(MethodCallExpr.class);

			for (int j = 0; j < methodCallExprs.size(); j++) {
				MethodCallExpr mce = methodCallExprs.get(j);
				
				mce.getArguments();

				methodCallNames.add(mce.getName().toString() + ":" + mce.getArguments().size());

				System.out.println(mce.getName().toString() + ":" + mce.getArguments().size());

				isMethodMatch = true;
				List<String> fullyQualifiedInterfaceNames = new ArrayList<>();
				List<String> fullyQualifiedSuperClassNames = new ArrayList<>();
				String fullyQualifiedClassName = "";
				try {
					ResolvedMethodDeclaration resolvedMethodDeclaration = mce.resolve();

					fullyQualifiedClassName = resolvedMethodDeclaration.getPackageName() + "."
							+ resolvedMethodDeclaration.getClassName();

					fullyQualifiedSuperClassNames
							.addAll(resolvedMethodDeclaration.declaringType().asClass().getAllSuperClasses().stream()
									.map(type -> type.getQualifiedName()).collect(Collectors.toList()));
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

					System.out.println(fullyQualifiedClassName);
					System.out.println(fullyQualifiedSuperClassNames);
					System.out.println(fullyQualifiedInterfaceNames);
					isResolved = true;

				} catch (UnsolvedSymbolException use) {
					System.out.println("\t\tunsolvedSymbolException in resolveFile");
					System.out.println("\t\tsymbol is " + use.getName());
				} catch (java.lang.IllegalAccessError iae) {
					System.out.println("\t!!! A shocking IllegalAccessError!");
					iae.printStackTrace();
					System.out.println("\t!!! Ignore it!");
				}

			}
		}

	}

}
