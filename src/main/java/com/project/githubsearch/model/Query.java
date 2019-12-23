package com.project.githubsearch.model;

import java.util.ArrayList;
import java.util.List;

public class Query {
	private String fullyQualifiedName;
	private String method;
	private List<String> arguments;

	private List<String> additionalKeywords = new ArrayList<>();
	
	public Query() {
		this.fullyQualifiedName = "";
		this.method = "";
		this.arguments = new ArrayList<String>();
	}

	/**
	 * @return the arguments
	 */
	public List<String> getArguments() {
		return arguments;
	}

	/**
	 * @return the fullyQualifiedName
	 */
	public String getFullyQualifiedClassName() {
		return fullyQualifiedName;
	}

	/**
	 * @return the method
	 */
	public String getMethod() {
		return method;
	}

	/**
	 * @param fullyQualifiedName the fullyQualifiedName to set
	 */
	public void setFullyQualifiedClassName(String fullyQualifiedName) {
		this.fullyQualifiedName = fullyQualifiedName;
	}

	/**
	 * @param method the method to set
	 */
	public void setMethod(String method) {
		this.method = method;
	}

	/**
	 * @param arguments the arguments to set
	 */
	public void setArguments(List<String> arguments) {
		this.arguments = arguments;
	}

	
	
	public List<String> getAdditionalKeywords() {
		return additionalKeywords;
	}

	public void setAdditionalKeywords(List<String> additionalKeywords) {
		this.additionalKeywords = additionalKeywords;
	}

	public boolean isQueryForConstructor() {
		return this.method.contains("init>");
	}

	@Override
	public String toString() {
		String query = "" + this.fullyQualifiedName + "#" + this.method;

		return query;
	}

	public String toStringRequest() {

		
		String additionalKeywords = this.additionalKeywords.isEmpty() ? "" : String.join(" ", this.additionalKeywords);
				
		if (!this.isQueryForConstructor()) {
			String methodSuffix = this.method + "(";
			if (fullyQualifiedName.startsWith("java.lang")) { // import by default
				return methodSuffix + " " +additionalKeywords;
			}
			return this.fullyQualifiedName + " " + methodSuffix + " " + additionalKeywords;
			
		} else {
			String[] splitted = this.getFullyQualifiedClassName().split("\\.");
			String className = splitted[splitted.length - 1];
			String methodSuffix = "new " + className + "(";
			if (fullyQualifiedName.startsWith("java.lang")) { // import by default
				return methodSuffix + " " + additionalKeywords;
			}
			return this.fullyQualifiedName + " " + methodSuffix + " " + additionalKeywords;

		}
	}

}
