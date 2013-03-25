/**
 * Copyright (c) 2000-2013 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.search.solr.suggest;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.QuerySuggester;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.search.SearchException;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;

import java.io.IOException;
import java.io.StringReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.search.spell.StringDistance;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

/**
 * @author Daniela Zapata
 * @author David Gonzalez
 * @author Michael C. Han
 */
public class SolrQuerySuggesterImpl implements QuerySuggester {

	public void setCollator(Collator collator) {
		_collator = collator;
	}

	public void setNGramQueryBuilder(NGramQueryBuilder nGramQueryBuilder) {
		_nGramQueryBuilder = nGramQueryBuilder;
	}

	public void setSolrServer(SolrServer solrServer) {
		_solrServer = solrServer;
	}

	public void setStringDistance(StringDistance stringDistance) {
		_stringDistance = stringDistance;
	}

	public void setThreshold(float threshold) {
		_threshold = threshold;
	}

	public String spellCheckKeywords(SearchContext searchContext)
		throws SearchException {

		Map<String, List<String>> mapSuggestions = spellCheckKeywords(
			searchContext, 1);

		List<String> tokens = tokenizeKeywords(
			searchContext.getKeywords(), searchContext.getLocale());

		return _collator.collate(mapSuggestions, tokens);
	}

	public Map<String, List<String>> spellCheckKeywords(
			SearchContext searchContext, int maxSuggestions)
		throws SearchException {

		Map<String, List<String>> suggestions =
			new HashMap<String, List<String>>();

		List<String> originals = tokenizeKeywords(
			searchContext.getKeywords(), searchContext.getLocale());

		for (String original : originals) {
			List<String> similarTokens = suggestTokenSimilars(
				searchContext.getLocale(), maxSuggestions, original);

			suggestions.put(original, similarTokens);
		}

		return suggestions;
	}

	public String[] suggestKeywordQueries(SearchContext searchContext, int max)
		throws SearchException {

		SolrQuery solrQuery = new SolrQuery();

		StringBundler sb = new StringBundler(5);

		sb.append(Field.KEYWORD_SEARCH);
		sb.append(StringPool.COLON);
		sb.append(StringPool.QUOTE);
		sb.append(searchContext.getKeywords());
		sb.append(StringPool.QUOTE);

		solrQuery.setRequestHandler(_suggesterURL);
		solrQuery.setQuery(sb.toString());
		solrQuery.setRows(max);

		String companyIdFilterQuery = Field.COMPANY_ID.concat(
			StringPool.COLON).concat(
				Long.toString(searchContext.getCompanyId()));

		solrQuery.setFilterQueries(companyIdFilterQuery);

		try {
			QueryResponse queryResponse = _solrServer.query(solrQuery);

			SolrDocumentList solrDocumentList = queryResponse.getResults();

			int numDocuments = solrDocumentList.size();

			String[] results = new String[numDocuments];

			for (int i = 0; i < numDocuments; i++) {
				SolrDocument solrDocument = solrDocumentList.get(i);

				results[i] = (String)solrDocument.getFieldValue(
					Field.KEYWORD_SEARCH);
			}

			return results;
		}
		catch (Exception e) {
			if (_log.isDebugEnabled()) {
				_log.debug("Unable to execute Solr query", e);
			}

			throw new SearchException(e.getMessage());
		}
	}

	public List<String> suggestTokenSimilars(
			Locale locale, int maxSuggestions, String token)
		throws SearchException {

		SolrQuery solrQuery = _nGramQueryBuilder.getNGramQuery(token);

		Map<String, Float> words = new HashMap<String, Float>();

		searchTokenSimilars(locale, token, solrQuery, words);

		ValueComparator valueComparator = new ValueComparator(words);

		TreeMap<String, Float> sortedWords = new TreeMap<String, Float>(
			valueComparator);

		sortedWords.putAll(words);

		List<String> listWords = new ArrayList(sortedWords.keySet());

		return listWords.subList(0, Math.min(maxSuggestions, listWords.size()));
	}

	protected void searchTokenSimilars(
			Locale locale, String token, SolrQuery solrQuery,
			Map<String, Float> words)
		throws SearchException {

		solrQuery.addFilterQuery("spellcheck:true");

		solrQuery.addFilterQuery("locale:" + locale.toString());

		try {
			QueryResponse queryResponse = _solrServer.query(
				solrQuery, SolrRequest.METHOD.POST);

			SolrDocumentList solrDocumentList = queryResponse.getResults();

			int numResults = solrDocumentList.size();

			Map<String, Float> tokenSuggestions = new HashMap<String, Float>();

			boolean foundWord = false;

			for (int i = 0; i < numResults; i++) {

				SolrDocument solrDocument = solrDocumentList.get(i);

				String suggestion = ((List<String>)solrDocument.get(
					"word")).get(0);

				String strWeight = ((List<String>)solrDocument.get(
					"weight")).get(0);

				float weight = Float.parseFloat(strWeight);

				if (suggestion.equalsIgnoreCase(token)) {
					words.put(token, weight);
					foundWord = true;
					break;
				}

				float distance = _stringDistance.getDistance(token, suggestion);

				if (distance > _threshold) {
					Float normalizedWeight = weight + distance;
					tokenSuggestions.put(suggestion, normalizedWeight);
				}
			}

			if (!foundWord) {
				if (tokenSuggestions.isEmpty()) {
					words.put(token, 0f);
				}
				else {
					words.putAll(tokenSuggestions);
				}
			}
		}
		catch (Exception e) {
			if (_log.isDebugEnabled()) {
				_log.debug("Unable to execute Solr query", e);
			}

			throw new SearchException(e.getMessage());
		}
	}

	protected List<String> tokenizeKeywords(String keywords, Locale locale)
		throws SearchException {

		List<String> result = new ArrayList<String>();

		TokenStream tokenStream = null;

		try {
			tokenStream = _analyzer.tokenStream(
				locale.toString(), new StringReader(keywords));

			CharTermAttribute charTermAttribute = tokenStream.addAttribute(
				CharTermAttribute.class);

			tokenStream.reset();

			while (tokenStream.incrementToken()) {
				result.add(charTermAttribute.toString());
			}

			tokenStream.end();
		}
		catch (IOException e) {
			throw new SearchException(e);
		}
		finally {
			if (tokenStream != null) {
				try {
					tokenStream.close();
				}
				catch (IOException e) {
					if (_log.isWarnEnabled()) {
						_log.warn("Unable to close token stream", e);
					}
				}
			}

		}

		return result;
	}

	private static Log _log = LogFactoryUtil.getLog(
		SolrQuerySuggesterImpl.class);

	private Analyzer _analyzer;
	private Collator _collator;
	private NGramQueryBuilder _nGramQueryBuilder;
	private SolrServer _solrServer;
	private StringDistance _stringDistance;
	private String _suggesterURL = "/select";
	private float _threshold;

}