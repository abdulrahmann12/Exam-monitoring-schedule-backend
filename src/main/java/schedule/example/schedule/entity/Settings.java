package schedule.example.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import schedule.example.schedule.entity.enums.ThemeMode;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
}