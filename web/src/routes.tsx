import { Route, Routes } from 'react-router'
import { ControlRoomPage } from './pages/ControlRoomPage'
import { CrewPage } from './pages/CrewPage'
import { HomePage } from './pages/HomePage'
import { HospitalPage } from './pages/HospitalPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PatientFilePage } from './pages/PatientFilePage'
import { PatientPage } from './pages/PatientPage'

/**
 * Address -> page. The four apps share this one web app; each has its own address. Until the login (SEC), the
 * hospital, unit or patient is chosen by the id in the address.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/control" element={<ControlRoomPage />} />
      <Route path="/control/patients/:patientId" element={<PatientFilePage />} />
      <Route path="/hospital/:facilityId" element={<HospitalPage />} />
      <Route path="/crew/:unitId" element={<CrewPage />} />
      <Route path="/patient/:patientId" element={<PatientPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
