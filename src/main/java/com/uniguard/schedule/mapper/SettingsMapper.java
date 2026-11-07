package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.settings.SettingsRequest;
import com.uniguard.schedule.dto.settings.SettingsResponse;
import com.uniguard.schedule.entity.Settings;

@Mapper(config = CentralMapperConfig.class)
public interface SettingsMapper {

	SettingsResponse toResponse(Settings settings);

	@Mapping(target = "documentSignatures", ignore = true)
	Settings toEntity(SettingsRequest request);

	@Mapping(target = "documentSignatures", ignore = true) // handled in SettingsService (null = keep)
	void updateEntity(SettingsRequest request, @MappingTarget Settings settings);
}