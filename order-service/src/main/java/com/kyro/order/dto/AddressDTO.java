package com.kyro.order.dto;

import com.kyro.order.OrderAddress;

/** Address DTO for representing shipping address details in orders. */
public class AddressDTO {
  private Long id;
  private String fullName;
  private String province;
  private String district;
  private String ward;
  private String street;
  private String note;
  private String phoneNumber;

  public AddressDTO() {}

  public AddressDTO(OrderAddress address) {
    if (address != null) {
      this.id = address.getId();
      this.fullName = address.getFullName();
      this.province = address.getProvince();
      this.district = address.getDistrict();
      this.ward = address.getWard();
      this.street = address.getStreet();
      this.note = address.getNote();
      this.phoneNumber = address.getPhoneNumber();
    }
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
