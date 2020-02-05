package com.project.githubsearch;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class Cocci4JRunner {
	static Process p = null;
	
	public static List<String> match(File cocci, String pathToFiles) {
		
		p = null;
		try {
			p = Runtime.getRuntime().exec("spatch --sp-file " + cocci.toString() + " " + pathToFiles);
		} catch (IOException e1) {
			throw new RuntimeException(e1);
		}
		new Thread(new Runnable() {
		    public void run() {
		     BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
		     String line = null; 

		     try {
		        while ((line = input.readLine()) != null)
		            System.out.println(line);
		     } catch (IOException e) {
		            e.printStackTrace();
		     }
		    }
		}).start();

		try {
			p.waitFor();
		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		return null;
		
	}
}
