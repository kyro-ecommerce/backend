package com.kyro.order;

import jakarta.persistence.*;

/** Persisted snapshot of the shipping address for an order. Decoupled from the User database. */
@Entity
@Table(name = "order_address")
public class OrderAddress {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String fullName;
  private String province;
  private String district;
  private String ward;
  private String street;
  private String note;
  private String phoneNumber;

  public OrderAddress() {}

  public OrderAddress(
      Long id,
      String fullName,
      String province,
      String district,
      String ward,
      String street,
      String note,
      String phoneNumber) {
    this.id = id;
    this.fullName = fullName;
    this.province = province;
    this.district = district;
    this.ward = ward;
    this.street = street;
    this.note = note;
    this.phoneNumber = phoneNumber;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public String getProvince() {
    return province;
  }

  public void setProvince(String province) {
    this.province = province;
  }

  public String getDistrict() {
    return district;
  }

  public void setDistrict(String district) {
    this.district = district;
  }

  public String getWard() {
    return ward;
  }

  public void setWard(String ward) {
    this.ward = ward;
  }

  public String getStreet() {
    return street;
  }

  public void setStreet(String street) {
    this.street = street;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public void setPhoneNumber(String phoneNumber) {
    this.phoneNumber = phoneNumber;
  }
}
