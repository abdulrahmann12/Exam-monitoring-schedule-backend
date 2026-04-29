package schedule.example.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import schedule.example.schedule.entity.enums.ThemeMode;

import java.util.UUID;

@Entity
@Table(name = "settings")
public class Settings {

	@Id
	private UUID id;

	@Column(nullable = false, length = 160)
	private String systemName;

	@Column(length = 200)
	private String appTagline;

	@Column(length = 500)
	private String logoUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ThemeMode theme;

	@Column(nullable = false, length = 200)
	private String universityName;

	@Column(length = 160)
	private String department;

	@Column(nullable = false, length = 120)
	private String examPeriod;

	public Settings() {
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getSystemName() {
		return systemName;
	}

	public void setSystemName(String systemName) {
		this.systemName = systemName;
	}

	public String getAppTagline() {
		return appTagline;
	}

	public void setAppTagline(String appTagline) {
		this.appTagline = appTagline;
	}

	public String getLogoUrl() {
		return logoUrl;
	}

	public void setLogoUrl(String logoUrl) {
		this.logoUrl = logoUrl;
	}

	public ThemeMode getTheme() {
		return theme;
	}

	public void setTheme(ThemeMode theme) {
		this.theme = theme;
	}

	public String getUniversityName() {
		return universityName;
	}

	public void setUniversityName(String universityName) {
		this.universityName = universityName;
	}

	public String getDepartment() {
		return department;
	}

	public void setDepartment(String department) {
		this.department = department;
	}

	public String getExamPeriod() {
		return examPeriod;
	}

	public void setExamPeriod(String examPeriod) {
		this.examPeriod = examPeriod;
	}
}