package be.stijnhooft.task.backend.notification.repository;

import be.stijnhooft.task.backend.notification.domain.PushSubscription;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PushSubscriptionRepository extends CrudRepository<PushSubscription, UUID> {

    /// The endpoint is what a device knows about itself; the id is ours. Registration and removal
    /// both arrive naming an endpoint.
    Optional<PushSubscription> findByEndpoint(String endpoint);
}
