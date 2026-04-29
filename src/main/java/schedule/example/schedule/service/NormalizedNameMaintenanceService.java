package schedule.example.schedule.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomRepository;

import java.util.List;

@Service
public class NormalizedNameMaintenanceService {

	private final PersonRepository personRepository;
	private final RoomRepository roomRepository;

	public NormalizedNameMaintenanceService(PersonRepository personRepository, RoomRepository roomRepository) {
		this.personRepository = personRepository;
		this.roomRepository = roomRepository;
	}

	@Transactional
	public void synchronizePeople() {
		List<Person> people = personRepository.findAllByNormalizedNameIsNull();
		if (people.isEmpty()) {
			return;
		}

		people.forEach(person -> person.setName(person.getName()));
		personRepository.flush();
	}

	@Transactional
	public void synchronizeRooms() {
		List<Room> rooms = roomRepository.findAllByNormalizedNameIsNull();
		if (rooms.isEmpty()) {
			return;
		}

		rooms.forEach(room -> room.setName(room.getName()));
		roomRepository.flush();
	}
}