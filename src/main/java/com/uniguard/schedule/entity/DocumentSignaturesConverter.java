package com.uniguard.schedule.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniguard.schedule.dto.settings.DocumentSignature;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/** Stores the export signature blocks as a short JSON list in one column. */
@Converter
public class DocumentSignaturesConverter implements AttributeConverter<List<DocumentSignature>, String> {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<DocumentSignature>> TYPE = new TypeReference<>() { };

	@Override
	public String convertToDatabaseColumn(List<DocumentSignature> value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return JSON.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Could not store the document signatures", ex);
		}
	}

	@Override
	public List<DocumentSignature> convertToEntityAttribute(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		try {
			return JSON.readValue(value, TYPE);
		} catch (JsonProcessingException ex) {
			return List.of(); // unreadable old value: show no signatures rather than failing the settings page
		}
	}
}
