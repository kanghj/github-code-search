package com.project.githubsearch.model;

import java.util.ArrayList;
import java.util.List;

public class ResolvedFile {
    private Query query;
    private String url;
    private String pathFile;
    private List<Integer> lines;
    private List<String> codes = new ArrayList<String>();

    public ResolvedFile(Query query, String url, String pathFile, List<Integer> lines, List<String> codes) {
        this.query = query;
        this.url = url;
        this.pathFile = pathFile;
        this.lines = lines;
        this.codes = codes;
    }

    /**
     * 
     */
    public Query getQuery() {
        return query;
    }

    /**
     * @return the url
     */
    public String getUrl() {
        return url;
    }

    /**
     * @return the pathFile
     */
    public String getPathFile() {
        return pathFile;
    }

    /**
     * @return the line
     */
    public List<Integer> getLines() {
        return lines;
    }

    /**
     * @return the codes
     */
    public List<String> getCodes() {
        return codes;
    }


    /**
     * @param queries the queries to set
     */
    public void setQuery(Query query) {
        this.query = query;
    }

    /**
     * @param url the url to set
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * @param pathFile the pathFile to set
     */
    public void setPathFile(String pathFile) {
        this.pathFile = pathFile;
    }

    /**
     * @param line the line to set
     */
    public void setLines(List<Integer> lines) {
        this.lines = lines;
    }

    /**
     * @param codes the codes to set
     */
    public void setCodes(List<String> codes) {
        this.codes = codes;
    }

}