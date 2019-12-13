package com.project.githubsearch.model;

import java.util.ArrayList;

public class ResolvedFiles {

    ArrayList<ResolvedFile> resolvedFiles;

    public ResolvedFiles() {
        this.resolvedFiles = new ArrayList<ResolvedFile>();
    }

    /**
     * @return the resolvedFiles
     */
    public synchronized ArrayList<ResolvedFile> getResolvedFiles() {
        return resolvedFiles;
    }

    public synchronized void add(ResolvedFile resolvedFile) {
        this.resolvedFiles.add(resolvedFile);
    } 

}