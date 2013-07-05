/*******************************************************************************
 * Copyright 2010
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl-3.0.txt
 ******************************************************************************/
package de.tudarmstadt.ukp.dkpro.core.stanfordnlp;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.uima.util.Level.INFO;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.commons.lang.StringUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Type;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.CasConfigurableProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.resources.MappingProvider;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Triple;

/**
 * Stanford Named Entity Recognizer component.
 *
 * @author Richard Eckart de Castilho
 * @author Oliver Ferschke
 */
public class StanfordNamedEntityRecognizer
	extends JCasAnnotator_ImplBase
{
	/**
	 * Log the tag set(s) when a model is loaded.
	 */
	public static final String PARAM_PRINT_TAGSET = ComponentParameters.PARAM_PRINT_TAGSET;
	@ConfigurationParameter(name = PARAM_PRINT_TAGSET, mandatory = true, defaultValue="false")
	protected boolean printTagSet;

	/**
	 * Use this language instead of the document language to resolve the model.
	 */
	public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
	@ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false)
	protected String language;

	/**
	 * Variant of a model the model. Used to address a specific model if here are multiple models
	 * for one language.
	 */
	public static final String PARAM_VARIANT = ComponentParameters.PARAM_VARIANT;
	@ConfigurationParameter(name = PARAM_VARIANT, mandatory = false)
	protected String variant;

	/**
	 * Location from which the model is read.
	 */
	public static final String PARAM_MODEL_LOCATION = ComponentParameters.PARAM_MODEL_LOCATION;
	@ConfigurationParameter(name = PARAM_MODEL_LOCATION, mandatory = false)
	protected String modelLocation;

	/**
	 * Location of the mapping file for named entity tags to UIMA types.
	 */
	public static final String PARAM_NAMED_ENTITY_MAPPING_LOCATION = ComponentParameters.PARAM_NAMED_ENTITY_MAPPING_LOCATION;
	@ConfigurationParameter(name = PARAM_NAMED_ENTITY_MAPPING_LOCATION, mandatory = false)
	protected String mappingLocation;

	private CasConfigurableProviderBase<AbstractSequenceClassifier<? extends CoreMap>> modelProvider;
	private MappingProvider mappingProvider;

	@Override
	public void initialize(UimaContext aContext)
		throws ResourceInitializationException
	{
		super.initialize(aContext);

		modelProvider = new CasConfigurableProviderBase<AbstractSequenceClassifier<? extends CoreMap>>() {
			{
			    setContextObject(StanfordNamedEntityRecognizer.this);

				setDefault(GROUP_ID, "de.tudarmstadt.ukp.dkpro.core");
				setDefault(ARTIFACT_ID,
						"de.tudarmstadt.ukp.dkpro.core.stanfordnlp-model-ner-${language}-${variant}");

				setDefaultVariantsLocation(
						"de/tudarmstadt/ukp/dkpro/core/stanfordnlp/lib/ner-default-variants.map");
				setDefault(LOCATION, "classpath:/de/tudarmstadt/ukp/dkpro/core/stanfordnlp/lib/" +
						"ner-${language}-${variant}.properties");

				setOverride(LOCATION, modelLocation);
				setOverride(LANGUAGE, language);
				setOverride(VARIANT, variant);
			}

			@Override
			protected AbstractSequenceClassifier<? extends CoreMap> produceResource(URL aUrl) throws IOException
			{
				InputStream is = null;
				try {
					is = aUrl.openStream();
					if (aUrl.toString().endsWith(".gz")) {
						// it's faster to do the buffering _outside_ the gzipping as here
						is = new GZIPInputStream(is);
					}

					@SuppressWarnings("unchecked")
					AbstractSequenceClassifier<? extends CoreMap> classifier = CRFClassifier.getClassifier(is);

					if (printTagSet) {
						StringBuilder sb = new StringBuilder();
						sb.append("Model contains [").append(classifier.classIndex.size()).append("] tags: ");

						List<String> tags = new ArrayList<String>();
						for (String t : classifier.classIndex) {
							tags.add(t);
						}

						Collections.sort(tags);
						sb.append(StringUtils.join(tags, " "));
						getContext().getLogger().log(INFO, sb.toString());
					}

					return classifier;
				}
				catch (ClassNotFoundException e) {
					throw new IOException(e);
				}
				finally {
					closeQuietly(is);
				}
			}
		};

		mappingProvider = new MappingProvider();
		mappingProvider.setDefaultVariantsLocation(
				"de/tudarmstadt/ukp/dkpro/core/stanfordnlp/lib/ner-default-variants.map");
		mappingProvider.setDefault(MappingProvider.LOCATION, "classpath:/de/tudarmstadt/ukp/dkpro/" +
				"core/stanfordnlp/lib/ner-${language}-${variant}.map");
		mappingProvider.setDefault(MappingProvider.BASE_TYPE, NamedEntity.class.getName());
		mappingProvider.setOverride(MappingProvider.LOCATION, mappingLocation);
		mappingProvider.setOverride(MappingProvider.LANGUAGE, language);
		mappingProvider.setOverride(MappingProvider.VARIANT, variant);
	}

	@Override
	public void process(JCas aJCas)
		throws AnalysisEngineProcessException
	{
		CAS cas = aJCas.getCas();
		modelProvider.configure(cas);
		mappingProvider.configure(cas);

		// get the document text
		String documentText = cas.getDocumentText();

		// test the string
		List<Triple<String, Integer, Integer>> namedEntities = modelProvider.getResource()
				.classifyToCharacterOffsets(documentText);

		// get the named entities and their character offsets
		for (Triple<String, Integer, Integer> namedEntity : namedEntities) {
			int begin = namedEntity.second();
			int end = namedEntity.third();

			Type type = mappingProvider.getTagType(namedEntity.first());
			NamedEntity neAnno = (NamedEntity) cas.createAnnotation(type, begin, end);
			neAnno.setValue(namedEntity.first());
			neAnno.addToIndexes();
		}
	}
}
