package com.project.githubsearch;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

public class HJLoggingWriter extends Writer {

	Writer writer;
	Writer logWriter;
	
	public HJLoggingWriter(Writer writer) {
		this.writer = writer;
		
		try {
			logWriter = new BufferedWriter(new FileWriter("hj_logs.log"));
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {
		writer.write(cbuf, off, len);
		logWriter.write(cbuf, off, len);
		
	}

	@Override
	public void flush() throws IOException {
		writer.flush();
		logWriter.flush();
		
	}

	@Override
	public void close() throws IOException {
		writer.close();
		logWriter.close();
	}
	

}
