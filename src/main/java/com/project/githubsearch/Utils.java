package com.project.githubsearch;

public class Utils {

	// parameter for the request
	static final String PARAM_QUERY = "q"; //$NON-NLS-1$
	static final String PARAM_PAGE = "page"; //$NON-NLS-1$
	static final String PARAM_PER_PAGE = "per_page"; //$NON-NLS-1$
	static final String PARAM_SORT = "sort";

	// links from the response header
	static final String META_REL = "rel"; //$NON-NLS-1$
	static final String META_NEXT = "next"; //$NON-NLS-1$
	static final String DELIM_LINKS = ","; //$NON-NLS-1$
	static final String DELIM_LINK_PARAM = ";"; //$NON-NLS-1$

	// response code from github
	static final int BAD_CREDENTIAL = 401;
	static final int RESPONSE_OK = 200;
	static final int ABUSE_RATE_LIMITS = 403;
	static final int UNPROCESSABLE_ENTITY = 422;

}
