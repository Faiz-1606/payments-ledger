package com.example.ledger.user;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ledger.common.error.NotFoundException;

@Service
public class UserService {

    private final UserRepository userRepository;

    // Constructor injection: dependencies are final and visible; no field injection magic.
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Creates a user. The "exists?" check gives a friendly error in the common case, but it is
     * a check-then-act race: two requests can both pass it. The UNIQUE constraint on
     * users.email is what really guarantees uniqueness; we translate its violation into the
     * same 409. {@code saveAndFlush} forces the INSERT now so the violation surfaces here.
     */
    @Transactional
    public User createUser(String fullName, String email) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }
        try {
            return userRepository.saveAndFlush(new User(fullName.trim(), normalizedEmail));
        } catch (DataIntegrityViolationException raceLost) {
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }
    }

    @Transactional(readOnly = true)
    public User getUser(long userId) {
        return userRepository.findById(userId).orElseThrow(() -> NotFoundException.user(userId));
    }
}
