package schedule.example.schedule.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse;
import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse.TopAssignedPerson;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.RoomRepository;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final PersonRepository personRepository;
    private final RoomRepository roomRepository;
    private final RoomAssignmentRepository roomAssignmentRepository;

    public DashboardService(
            PersonRepository personRepository,
            RoomRepository roomRepository,
            RoomAssignmentRepository roomAssignmentRepository
    ) {
        this.personRepository = personRepository;
        this.roomRepository = roomRepository;
        this.roomAssignmentRepository = roomAssignmentRepository;
    }

    public DashboardSummaryResponse getSummary() {
        long chiefs = personRepository.countByRoleAndActiveTrue(PersonRole.CHIEF_INVIGILATOR);
        long invigilators = personRepository.countByRoleAndActiveTrue(PersonRole.INVIGILATOR);
        long rooms = roomRepository.countByActiveTrue();
        long totalAssignments = roomAssignmentRepository.count();

        List<TopAssignedPerson> top = personRepository
                .findTop10ByActiveTrueOrderByTotalAssignmentsDesc(
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "totalAssignments")))
                .stream()
                .map(p -> new TopAssignedPerson(
                        p.getId(),
                        p.getName(),
                        p.getRole().name(),
                        p.getTotalAssignments()))
                .toList();

        return new DashboardSummaryResponse(chiefs, invigilators, rooms, totalAssignments, top);
    }
}
