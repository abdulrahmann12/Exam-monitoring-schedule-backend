package schedule.example.schedule.dto.person;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.Gender;
import schedule.example.schedule.entity.enums.WeekDay;

import java.util.Set;

public record PersonRequest(
	@NotBlank(message = "Name is required")
	@Size(max = 160, message = "Name must be at most 160 characters")
	String name,

	@NotBlank(message = "Department is required")
	@Size(max = 160, message = "Department must be at most 160 characters")
	String department,

	@Email(message = "Email must be a valid email address")
	@Size(max = 255, message = "Email must be at most 255 characters")
	String email,

	@NotNull(message = "Role is required")
	PersonRole role,

	@NotEmpty(message = "At least one available day is required")
	Set<WeekDay> availableDays,

	/** Optional; needed only for gender-separated halls. */
	Gender gender,

	/** Optional subject codes this person teaches (they won't be assigned to those exams). */
	@Size(max = 50, message = "At most 50 subject codes")
	Set<@Size(max = 40, message = "Subject code must be at most 40 characters") String> subjectCodes
) {
}