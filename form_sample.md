Data Pemilik Manfaat
  Non nasabah - nasabah: radio button

 -> NTB -> wni:
  Data Pemilik Manfaat 
   Identitas Pemilik Manfaat
  kewarganegaraan=indonesia, wna (different childform)
    foto ktp (file upload)
    nomor identitas: freetext
    nama lengkap freetext
    tanggal lahir date (yyyy/mm/dd)
    Jenis kelamin: male/female

  -> screening

  informasi pribadi pemilik manfaat
    tempat lahir: free text
    status perkawinan: (radio button) - menikah, tidak menikah, janda
    hubungan dengan nasabah: free text
    nomor telpon rumah: free text
    nomor handphone: free text

  Alamat pemilik manfaat sesuai identitas
    negara: repositories
    alamat: text box
    kode-pos: repositories
      kelurahan: disable, repo,  text
      kecamatan:disable, repo, text
      kabupaten:disable, repo, text
      provinsi: diable, repo, text

  Pekerjaan Pemilik
   Informasi pekerjaan (section)
    Jenis pekerjaan: repo, drop down - depends on
    Jabatan: repo, drop down, depends on
    industry: repo drop down, depends on
    sub-industry: drop down, repo, depends on
    sumber penghasilan: drop down, repo, depends on
    penghasilan perbulan: double, free-text
    nama badan usaha: free-text, string 
    nomor telpon kantor: prefixed_phone, string
   Alamat badan Usaha
    Negara: dropdown (conditional optional) 
    alamat: text-box
    kode pos... (idem as above - check the alamat pemilik manfaat...)


  Screening seqment: 
    Hasil Screening: pending - Approval di akhir, butuh approval
    informasi Pribadi Pemilik Manfaat
      tempat lahir: free-text, string 1-1
      status perkawinan: radio button menikah, tidak minikah, janda/duda
      hubungan dengan nasabah: string free-text, 1-1
      nomor telpon rumah: string, free text, 1-1
      nomor handphone: string, free text phone validation, 1-1
    Documen pendukung:
      jenis document: option: kartu keluarga, akta lahir, document lain
       (child form) -:> put in obs parameter
         Kartu keluarga 
           Document dual id: file upload
           nomor ID Document: string, 1-1, freetext
           tanggal terbit: date 
         Akta lahir 
           Document Dual ID: file upload
           nomor id dokument: string, free-text, 1-1
         Document ;aonnya
           Document Dual id: file upload
           nomor id document: string freetext 1-1


beaware with wna - wni
