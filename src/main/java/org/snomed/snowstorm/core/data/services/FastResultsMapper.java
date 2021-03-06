package org.snomed.snowstorm.core.data.services;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.core.DefaultResultMapper;
import org.springframework.data.elasticsearch.core.EntityMapper;
import org.springframework.data.elasticsearch.core.aggregation.AggregatedPage;
import org.springframework.data.elasticsearch.core.aggregation.impl.AggregatedPageImpl;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentEntity;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.context.MappingContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * This class can produce results from Elasticsearch much faster than the default mapper but only returns the conceptId.
 * To use this feature set the fields param in your Elasticsearch query to conceptId (or sourceId for relationships).
 */
public class FastResultsMapper extends DefaultResultMapper {

	private Logger logger = LoggerFactory.getLogger(getClass());

	private Map<Class, Function<SearchHit, Object>> mapFunctions = MapBuilder.newMapBuilder(new HashMap<Class, Function<SearchHit, Object>>())
			.put(Concept.class, hit -> new Concept(hit.getFields().get(Concept.Fields.CONCEPT_ID).getValue()))
			.put(Description.class, hit -> new Description().setConceptId(hit.getFields().get(Description.Fields.CONCEPT_ID).getValue()))
			.put(Relationship.class, hit -> new Relationship().setSourceId(hit.getFields().get(Relationship.Fields.SOURCE_ID).getValue()))
			.put(ReferenceSetMember.class, hit -> new ReferenceSetMember()
					.setReferencedComponentId(hit.getFields().get(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID).getValue())
					.setConceptId(hit.getFields().get(ReferenceSetMember.Fields.CONCEPT_ID).getValue())
			)
			.put(QueryConcept.class, hit -> {
				QueryConcept queryConcept = new QueryConcept();
				queryConcept.setConceptIdL(hit.getFields().get(QueryConcept.Fields.CONCEPT_ID).getValue());
				if (hit.getFields().containsKey(QueryConcept.Fields.ATTR_MAP)) {
					queryConcept.setAttrMap(hit.getFields().get(QueryConcept.Fields.ATTR_MAP).getValue());
				}
				return queryConcept;
			})
			.map();

	private final MappingContext<? extends ElasticsearchPersistentEntity<?>, ElasticsearchPersistentProperty> mappingContext;

	public FastResultsMapper(MappingContext<? extends ElasticsearchPersistentEntity<?>, ElasticsearchPersistentProperty> mappingContext, EntityMapper entityMapper) {
		super(mappingContext, entityMapper);
		this.mappingContext = mappingContext;
	}

	@Override
	public <T> AggregatedPage<T> mapResults(SearchResponse response, Class<T> clazz, Pageable pageable) {
		SearchHit[] hits = response.getHits().getHits();

		logger.debug("hits.length {}, mapFunctions.containsKey(clazz) {}, hits[0].getFields().isEmpty() {}", hits.length, mapFunctions.containsKey(clazz), hits.length > 0 ? hits[0].getFields().isEmpty() : "");

		if (hits.length == 0 || !mapFunctions.containsKey(clazz) || hits[0].getFields().isEmpty()) {
			logger.debug("Loading {} {} using STANDARD result mapping.", hits.length, clazz.getSimpleName());
			return super.mapResults(response, clazz, pageable);
		}

		logger.debug("Loading {} {} using FAST result mapping.", hits.length, clazz.getSimpleName());

		long totalHits = response.getHits().getTotalHits();

		List<T> results = new ArrayList<>();
		for (SearchHit searchHit : response.getHits()) {
			@SuppressWarnings("unchecked")
			T result = (T) mapFunctions.get(clazz).apply(searchHit);
			setPersistentEntityId(result, searchHit.getId(), clazz);
			results.add(result);
		}

		return new AggregatedPageImpl<>(results, pageable, totalHits, response.getAggregations());
	}

	// Copied from super class
	private <T> void setPersistentEntityId(T result, String id, Class<T> clazz) {
		if (this.mappingContext != null && clazz.isAnnotationPresent(Document.class)) {
			ElasticsearchPersistentEntity<?> persistentEntity = this.mappingContext.getPersistentEntity(clazz);
			PersistentProperty<?> idProperty = persistentEntity.getIdProperty();
			if (idProperty != null && idProperty.getType().isAssignableFrom(String.class)) {
				persistentEntity.getPropertyAccessor(result).setProperty(idProperty, id);
			}
		}

	}

}
