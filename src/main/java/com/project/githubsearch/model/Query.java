package com.project.githubsearch.model;

import java.util.ArrayList;
import java.util.List;

public class Query {
    private String fullyQualifiedName;
    private String method;
    private List<String> arguments;

    public Query() {
        this.fullyQualifiedName = "";
        this.method = "";
        this.arguments = new ArrayList<String>();
    }

    /**
     * @return the arguments
     */
    public 	List<String> getArguments() {
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


    @Override
    public String toString() {
        String query = ""  + this.fullyQualifiedName + "#" + this.method;

        return query;
    }

    public String toStringRequest() {
        String request = this.fullyQualifiedName + " " + this.method +"(";
 
        return request;
    }

}
