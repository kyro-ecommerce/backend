package com.kyro.auth;

import com.kyro.auth.dto.AddAddressRequest;
import com.kyro.auth.dto.AddressDTO;
import com.kyro.auth.dto.BasicUserDTO;
import com.kyro.auth.dto.CreateUserRequest;
import com.kyro.auth.dto.RegisterRequest;
import com.kyro.auth.dto.UpdateUserRequest;
import com.kyro.auth.dto.UserDTO;
import com.kyro.auth.security.jwt.JwtUtils;
import com.kyro.auth.security.otp.OtpService;
import com.kyro.enums.UserRole;
import com.kyro.exceptions.DomainException;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final OtpService otpService;
  private final AddressRepository addressRepository;
  private final JwtUtils jwtUtils;

  public UserService(
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder,
      OtpService otpService,
      AddressRepository addressRepository,
      JwtUtils jwtUtils) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.otpService = otpService;
    this.addressRepository = addressRepository;
    this.jwtUtils = jwtUtils;
  }

  public User createUser(CreateUserRequest request) {
    return Optional.of(request)
        .filter(user -> !userRepository.existsByEmail(request.getEmail()))
        .map(
            req -> {
              User user = new User();
              user.setFirstName(req.getFirstName());
              user.setLastName(req.getLastName());
              user.setEmail(req.getEmail());
              user.setPassword(passwordEncoder.encode(req.getPassword()));

              // Default role is CUSTOMER
              Role customerRole =
                  roleRepository
                      .findByName(UserRole.CUSTOMER)
                      .orElseThrow(() -> new RuntimeException("Role CUSTOMER không tìm thấy"));
              user.setRole(customerRole);

              return userRepository.save(user);
            })
        .orElseThrow(
            () -> new EntityExistsException("Email " + request.getEmail() + " already be used"));
  }

  @Transactional // Transactional consistency annotation
  public UserDTO updateUser(UpdateUserRequest request, Long userId) {
    User existingUser =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User " + userId + " not found"));

    existingUser.setFirstName(request.getFirstName());
    existingUser.setLastName(request.getLastName());
    existingUser.setPhone(request.getPhoneNumber());

    User updatedUser = userRepository.save(existingUser);
    return new UserDTO(updatedUser);
  }

  public User getUserById(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));
  }

  public void deleteUser(Long userId) {
    userRepository
        .findById(userId)
        .ifPresentOrElse(
            userRepository::delete,
            () -> {
              throw new EntityNotFoundException("User not found");
            });
  }

  @Transactional
  public UserDTO convertUserToDto(User user) {
    UserDTO userDTO = new UserDTO();
    userDTO.setId(user.getId());
    userDTO.setFirstName(user.getFirstName());
    userDTO.setLastName(user.getLastName());
    userDTO.setEmail(user.getEmail());
    userDTO.setRole(
        user.getRole() != null && user.getRole().getName() != null
            ? user.getRole().getName().name()
            : "UNKNOWN");
    userDTO.setMobile(user.getPhone());
    userDTO.setActive(user.isActive());
    userDTO.setAddresses(user.getAddress());
    userDTO.setCreatedAt(user.getCreatedAt());
    userDTO.setImageUrl(user.getImageUrl());
    userDTO.setOauthProvider(user.getOauthProvider());
    return userDTO;
  }

  @Transactional
  public void registerUser(RegisterRequest request) {
    // Check if email already exists
    if (userRepository.existsByEmail(request.getEmail())) {
      throw new EntityExistsException("Email already exists");
    }

    User user = new User();
    user.setEmail(request.getEmail());
    user.setFirstName(request.getFirstName());
    user.setLastName(request.getLastName());
    user.setCreatedAt(LocalDateTime.now());
    user.setPassword(passwordEncoder.encode(request.getPassword()));
    user.setActive(false);

    // Assign CUSTOMER role
    Role role =
        roleRepository
            .findByName(UserRole.CUSTOMER)
            .orElseGet(() -> roleRepository.save(new Role(UserRole.CUSTOMER)));

    user.setRole(role);
    userRepository.save(user);

    // Generate and send OTP
    String otp = otpService.generateOtp(request.getEmail());
    otpService.sendOtpEmail(request.getEmail(), otp);
  }

  public boolean verifyOtp(OtpVerificationRequest request) {
    // Verify OTP and activate account
    boolean isValid = otpService.validateOtp(request.getEmail(), request.getOtp());
    return isValid;
  }

  public UserDTO findUserProfileByJwt(String jwt) {
    if (jwt != null && jwt.startsWith("Bearer ")) {
      jwt = jwt.substring(7); // Remove "Bearer " prefix
    }
    String email = jwtUtils.getEmailFromToken(jwt);
    User user = userRepository.findByEmail(email);

    if (user == null) {
      throw new EntityNotFoundException("User not found " + email);
    }
    return convertUserToDto(user);
  }

  public User findUserByJwt(String jwt) {
    if (jwt != null && jwt.startsWith("Bearer ")) {
      jwt = jwt.substring(7); // Remove "Bearer " prefix
    }
    String email = jwtUtils.getEmailFromToken(jwt);
    User user = userRepository.findByEmail(email);

    if (user == null) {
      throw new EntityNotFoundException("User not found " + email);
    }
    return user;
  }

  public AddressDTO addUserAddress(User user, AddAddressRequest request) {
    List<Address> address = user.getAddress();
    if (address == null) {
      address = new ArrayList<>();
    }
    Address newAddress = new Address();
    newAddress.setFullName(request.getFullName());
    newAddress.setProvince(request.getProvince());
    newAddress.setDistrict(request.getDistrict());
    newAddress.setWard(request.getWard());
    newAddress.setStreet(request.getStreet());
    newAddress.setNote(request.getNote());
    newAddress.setPhoneNumber(request.getPhoneNumber());
    newAddress.setUser(user);
    address.add(newAddress);
    addressRepository.save(newAddress);
    return new AddressDTO(newAddress);
  }

  @Transactional
  public AddressDTO updateAddress(Long addressId, AddAddressRequest request, String email) {
    User user = userRepository.findByEmail(email);
    if (user == null) {
      throw new EntityNotFoundException("User not found: " + email);
    }
    Address address =
        addressRepository
            .findById(addressId)
            .orElseThrow(
                () -> new EntityNotFoundException("Address not found with ID: " + addressId));
    verifyAddressOwnership(user, address);

    if (request.getFullName() != null && !request.getFullName().trim().isEmpty()) {
      address.setFullName(request.getFullName().trim());
    }
    if (request.getProvince() != null && !request.getProvince().trim().isEmpty()) {
      address.setProvince(request.getProvince().trim());
    }
    if (request.getDistrict() != null && !request.getDistrict().trim().isEmpty()) {
      address.setDistrict(request.getDistrict().trim());
    }
    if (request.getWard() != null && !request.getWard().trim().isEmpty()) {
      address.setWard(request.getWard().trim());
    }
    if (request.getStreet() != null && !request.getStreet().trim().isEmpty()) {
      address.setStreet(request.getStreet().trim());
    }
    if (request.getNote() != null) {
      address.setNote(request.getNote());
    }
    if (request.getPhoneNumber() != null && !request.getPhoneNumber().trim().isEmpty()) {
      address.setPhoneNumber(request.getPhoneNumber().trim());
    }

    Address updated = addressRepository.save(address);
    return new AddressDTO(updated);
  }

  @Transactional
  public void deleteAddress(Long addressId, String email) {
    User user = userRepository.findByEmail(email);
    if (user == null) {
      throw new EntityNotFoundException("User not found: " + email);
    }
    Address address =
        addressRepository
            .findById(addressId)
            .orElseThrow(
                () -> new EntityNotFoundException("Address not found with ID: " + addressId));
    verifyAddressOwnership(user, address);

    if (user.getAddress() != null) {
      user.getAddress().removeIf(a -> a.getId() != null && a.getId().equals(addressId));
    }
    addressRepository.delete(address);
  }

  private void verifyAddressOwnership(User user, Address address) {
    if (address.getUser() == null
        || address.getUser().getId() == null
        || !address.getUser().getId().equals(user.getId())) {
      throw new DomainException(
          HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thao tác trên địa chỉ này");
    }
  }

  @Transactional
  public BasicUserDTO changeUserRole(Long userId, String roleName) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    UserRole userRole;
    try {
      userRole = UserRole.valueOf(roleName.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Role not valid: " + roleName);
    }

    Role role =
        roleRepository
            .findByName(userRole)
            .orElseThrow(() -> new RuntimeException("Role " + roleName + " not found"));
    user.setRole(role);

    User res = userRepository.save(user);
    BasicUserDTO userDTO = convertToBasicDto(res);
    return userDTO;
  }

  public void forgotPassword(String email, String newPassword) {
    User user = userRepository.findByEmail(email);
    if (user == null) {
      throw new EntityNotFoundException("User not found");
    }
    user.setPassword(passwordEncoder.encode(newPassword));
    userRepository.save(user);
  }

  public BasicUserDTO convertToBasicDto(User user) {
    BasicUserDTO dto = new BasicUserDTO();
    dto.setId(user.getId());
    dto.setEmail(user.getEmail());
    dto.setFirstName(user.getFirstName());
    dto.setLastName(user.getLastName());
    dto.setMobile(user.getPhone());
    dto.setActive(user.isActive());
    dto.setBanned(user.isBanned());
    dto.setRole(
        user.getRole() != null && user.getRole().getName() != null
            ? user.getRole().getName().name()
            : "UNKNOWN");
    dto.setCreatedAt(user.getCreatedAt());
    dto.setImageUrl(user.getImageUrl());
    dto.setOauthProvider(user.getOauthProvider());
    return dto;
  }
}
