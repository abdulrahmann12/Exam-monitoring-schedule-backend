package schedule.example.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import schedule.example.schedule.dto.settings.SettingsRequest;
import schedule.example.schedule.dto.settings.SettingsResponse;
import schedule.example.schedule.entity.Settings;

@Mapper(config = CentralMapperConfig.class)
public interface SettingsMapper {

	SettingsResponse toResponse(Settings settings);

	Settings toEntity(SettingsRequest request);

	void updateEntity(SettingsRequest request, @MappingTarget Settings settings);
}