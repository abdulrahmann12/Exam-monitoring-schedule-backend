package com.uniguard.schedule.repository;

import java.util.UUID;

public interface GroupCountProjection {

	UUID getGroupId();

	long getTotal();
}
