package com.bdx.bwallet.server.web.filter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class CorsFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestOrigin = request.getHeader("Origin");
		boolean allowed = false;
		if (requestOrigin != null && requestOrigin.indexOf("mybwallet.com") >= 0)
			allowed = true;
		if (allowed) {
			if (request.getHeader("Access-Control-Request-Method") != null && "OPTIONS".equals(request.getMethod())) {
				// CORS "pre-flight" request
				response.addHeader("Access-Control-Allow-Credentials", "true");
				response.addHeader("Access-Control-Allow-Headers", "Accept,Origin,Content-Type");
				response.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS,HEAD,DELETE,PUT");
				response.addHeader("Access-Control-Allow-Origin", requestOrigin);
				response.addHeader("Access-Control-Max-Age", "1800");	// 30 min
			} else {
				response.addHeader("Access-Control-Allow-Credentials", "true");
				response.addHeader("Access-Control-Allow-Origin", requestOrigin);
				response.addHeader("Access-Control-Expose-Headers", "X-Cache-Date");
			}
		}
		filterChain.doFilter(request, response);
	}

}
