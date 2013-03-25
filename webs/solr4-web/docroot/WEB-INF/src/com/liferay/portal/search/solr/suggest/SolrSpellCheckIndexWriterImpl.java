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
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.search.SearchException;
import com.liferay.portal.kernel.search.SpellCheckIndexWriter;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.util.portlet.PortletProps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.common.SolrInputDocument;

/**
 * @author Daniela Zapata
 * @author David Gonzalez
 * @author Michael C. Han
 */
public class SolrSpellCheckIndexWriterImpl implements SpellCheckIndexWriter {

	public void deleteDocuments() throws SearchException {
		try {
			_solrServer.deleteByQuery("spellcheck:true");

			if (_commit) {
				_solrServer.commit();
			}
		}
		catch (Exception e) {
			throw new SearchException("Unable to delete documents", e);
		}
	}

	public void indexDictionaries(SearchContext searchContext)
		throws SearchException {

		deleteDocuments();

		for (String supportedLocale : _supportedLocales) {
			searchContext.setLocale(LocaleUtil.fromLanguageId(supportedLocale));

			indexDictionary(searchContext);
		}
	}

	public void indexDictionary(SearchContext searchContext)
		throws SearchException {

		Locale locale = searchContext.getLocale();
		String strLocale = locale.toString();

		String dictionaryDir = PortletProps.get(_DICTIONARY_DIRECTORY);

		String dictionaryRelativePath = dictionaryDir.concat(
			strLocale).concat(_DICTIONARY_EXTENSION_FILE);

		//todo need to refactor this portion
		String dictionaryCompletePath =
			SolrSpellCheckIndexWriterImpl.class.getClassLoader().getResource(
				dictionaryRelativePath).getFile();

		File fileExternal = new File(dictionaryCompletePath);

		doIndexDictionary(fileExternal, locale);

		StringBundler customRelativePath = new StringBundler(5);
		customRelativePath.append(dictionaryDir);
		customRelativePath.append(_CUSTOM_PREFIX);
		customRelativePath.append(StringPool.UNDERLINE);
		customRelativePath.append(strLocale);
		customRelativePath.append(_CUSTOM_EXTENSION_FILE);

		String customCompletePath =
			SolrSpellCheckIndexWriterImpl.class.getClassLoader().getResource(
				customRelativePath.toString()).getFile();

		File fileCustom = new File(customCompletePath);

		doIndexDictionary(fileCustom, locale);
	}

	public void setBatchSize(int batchSize) {
		_batchSize = batchSize;
	}

	public void setCommit(boolean commit) {
		_commit = commit;
	}

	public void setNGramHolderBuilder(NGramHolderBuilder nGramHolderBuilder) {
		_nGramHolderBuilder = nGramHolderBuilder;
	}

	public void setSolrServer(SolrServer solrServer) {
		_solrServer = solrServer;
	}

	public void setSupportedLocales(Set<String> supportedLocales) {
		_supportedLocales = supportedLocales;
	}

	protected void addDocument(
			Set<SolrInputDocument> solrDocuments,
			Locale locale, String token, int weight)
		throws SearchException {

		SolrInputDocument solrInputDocument = new SolrInputDocument();

		StringBundler sb = new StringBundler(6);
		sb.append("spellcheck");
		sb.append(StringPool.UNDERLINE);
		sb.append(token);
		sb.append(StringPool.UNDERLINE);
		sb.append(locale.toString());

		solrInputDocument.addField(Field.UID, sb.toString());
		solrInputDocument.addField("spellcheck", true);
		solrInputDocument.addField("word", token);
		solrInputDocument.addField("weight", String.valueOf(weight));
		solrInputDocument.addField("locale", locale.toString());

		addNGram(solrInputDocument, token);

		solrDocuments.add(solrInputDocument);
	}

	protected void addNGram(
			SolrInputDocument solrInputDocument, String text)
		throws SearchException {

		NGramHolder nGramHolder = _nGramHolderBuilder.buildNGramHolder(text);

		Map<String, List<String>> nGrams = nGramHolder.getNGrams();
		Map<String, String> nGramEnds = nGramHolder.getNGramEnds();
		Map<String, String> nGramStarts = nGramHolder.getNGramStarts();

		addNGramField(solrInputDocument, nGramEnds);
		addNGramField(solrInputDocument, nGramStarts);
		addNGramFields(solrInputDocument, nGrams);
	}

	protected void addNGramField(
		SolrInputDocument solrInputDocument, Map<String, String> nGrams) {

		for (Map.Entry<String, String> nGramEntry : nGrams.entrySet()) {
			solrInputDocument.addField(
				nGramEntry.getKey(), nGramEntry.getValue());
		}
	}

	protected void addNGramFields(
		SolrInputDocument solrInputDocument, Map<String, List<String>> nGrams) {

		for (Map.Entry<String, List<String>> nGramEntry : nGrams.entrySet()) {
			String fieldName = nGramEntry.getKey();

			for (String nGramValue : nGramEntry.getValue()) {
				solrInputDocument.addField(fieldName, nGramValue);
			}
		}
	}

	private void doIndexDictionary(File file, Locale locale)
		throws SearchException {

		Set<SolrInputDocument> solrDocuments = new HashSet<SolrInputDocument>();

		BufferedReader bufferedReader = null;

		try {
			FileReader fileReader = new FileReader(file);

			bufferedReader = new BufferedReader(fileReader);

			String line = bufferedReader.readLine();

			if (line == null) {
				return;
			}

			int lineCounter = 0;

			do {
				lineCounter++;

				String[] term = StringUtil.split(line, StringPool.SPACE);

				int weight = 0;

				if (term.length > 1) {
					try {
						weight = Integer.parseInt(term[1]);
					}
					catch (NumberFormatException e) {
						if (_log.isWarnEnabled()) {
							_log.warn("Invalid weight for term: " + term[0]);
						}
					}
				}

				addDocument(solrDocuments, locale, term[0], weight);

				line = bufferedReader.readLine();

				if ((lineCounter == _batchSize) || (line == null)) {
					_solrServer.add(solrDocuments);

					if (_commit) {
						_solrServer.commit();
					}

					solrDocuments.clear();

					lineCounter = 0;
				}
			}
			while (line != null);
		}
		catch (Exception e) {
			if (_log.isDebugEnabled()) {
				_log.debug("Unable to execute Solr query", e);
			}

			throw new SearchException(e.getMessage(), e);
		}
		finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				}
				catch (IOException ie) {
                    if (_log.isDebugEnabled()) {
                    	_log.debug("Unable to close dictionary file", ie);
                    }
				}
			}
		}
	}

	private static final int _DEFAULT_BATCH_SIZE = 10000;

	private static final String _CUSTOM_EXTENSION_FILE =".txt";
	private static final String _CUSTOM_PREFIX ="custom";
	private static final String _DICTIONARY_DIRECTORY =
		"dictionaries.directory";
	private static final String _DICTIONARY_EXTENSION_FILE =".txt";

	private static Log _log = LogFactoryUtil.getLog(
		SolrSpellCheckIndexWriterImpl.class);

	private int _batchSize = _DEFAULT_BATCH_SIZE;
	private boolean _commit;
	private NGramHolderBuilder _nGramHolderBuilder;
	private SolrServer _solrServer;
	private Set<String> _supportedLocales;

}