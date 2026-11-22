package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.VitalsReading;

/**
 * What the city's agents (patients' wearables and crews' apps) send to dispatch. Two implementations: {@link LiveEngine}
 * hands the messages to an engine in memory, {@link HttpLink} sends them to the running service over HTTP. The agents
 * cannot tell which one they talk to.
 * <p>
 * Every call may come from any agent thread and must return quickly: the implementation queues the work.
 */
interface CityLink {

    /**
     * A wearable reading.
     *
     * @param reading the reading
     */
    void reading(VitalsReading reading);

    /**
     * A crew answered its trip and set off.
     *
     * @param assignmentId the trip
     */
    void answered(String assignmentId);

    /**
     * A unit reports where it is.
     *
     * @param unitId the unit
     * @param where  its position
     */
    void position(String unitId, Location where);

    /**
     * A unit reached its patient.
     *
     * @param assignmentId the trip
     */
    void arrived(String assignmentId);

    /**
     * A repaired unit is ready again.
     *
     * @param unitId the unit
     */
    void backInService(String unitId);
}
