package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.dto.settings.SettingsRequest;
import schedule.example.schedule.dto.settings.SettingsResponse;
import schedule.example.schedule.service.SettingsService;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	private final SettingsService settingsService;

	public SettingsController(SettingsService settingsService) {
		this.settingsService = settingsService;
	}

	@GetMapping
	public SettingsResponse getSettings() {
		return settingsService.getSettings();
	}

	@PutMapping
	public SettingsResponse updateSettings(@Valid @RequestBody SettingsRequest request) {
		return settingsService.updateSettings(request);
	}
}